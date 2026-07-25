# Implementation Plan — Main Menu Batch 2 (Sidebar Relocation/Global Presence, Home/Activity Tab, Achievements Tab, Servers-Panel Friend Avatars)

Spec: `features/main-menu/specification-batch-2.md` (approved).

## Summary
Four independent items sharing the same platform-module files. This plan
sequences them to minimize rework: Item 1 (sidebar dock + global render) first
since it touches the shared `FriendSidebarWidget`/`MainMenuScreen`/injector
files every other item's UI sits inside; Item 4 (servers-panel avatars) next,
since it needs one small cross-feature API addition to
`features/server-join-presence` that should land and compile before the
main-menu side consumes it; Item 2 (Home/Activity tab) next, the smallest,
lowest-risk new-tab addition; Item 3 (Achievements tab) last, since it is
gated on a `javap` verification step whose outcome (FR-BB3.2a vs. FR-BB3.2b)
determines whether this item is a single pass or two (a fork-binding
prerequisite pass, then the tab).

No implementation code is written by this plan.

## Existing Implementation

### Shared per-platform files (all three modules: `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`, package `de.lazuli`)
- `friends/FriendSidebarWidget.java` — right-edge dock at three sites:
  `handleX()` (`screenWidth - HANDLE_WIDTH`), hit-test `testX` in `renderNow()`
  (`screenWidth - testWidth`), and the per-frame `setX(screenWidth - width)`
  call (spec cites `:256-258`, `:389-390`, `:450` for `fabric-26.2`;
  fabric-26.1 identical; fabric-1.21.11 offset -1/-2 lines per the
  post-launch-fixes-3 plan's already-confirmed offset pattern — re-verify
  exact line numbers per file at implementation time, don't assume the cited
  offset still holds after intervening bugfix commits `6fb32ec`).
- `friends/FabricFriendsSidebarInjector.java` — `isAllowListed(Screen)`
  (`:91-98`, six-type allow-list), `ScreenEvents.AFTER_INIT`-driven
  attach/detach (`:88`), `handleOnly` default logic (`:113-116`).
- `mainmenu/MainMenuScreen.java` — `panelX()`/`reservedWidth()`/`panelWidth()`
  (`:151-167`), tab bar `barX` computed at both the render call site and the
  `mouseClicked`/`mouseScrolled` call sites (`:226`, `:274`); own
  `FriendSidebarWidget` instance via `addRenderableWidget(sidebar)` (`:127`,
  base spec FR7.6).
- `mainmenu/ServersPanel.java` — Saved and Browser row rendering (base spec
  FR4.2/FR4.3); per-row ping-dot/player-count/lock-icon layout already
  flagged as tight (`specification-post-launch-fixes-3.md` FR-B3.4-3.6),
  confirming Item 4's avatar placement is a real layout-collision risk, not
  hypothetical.
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — existing enum
  (`WORLDS`/`SERVERS`/`STORE`/`WARDROBE`); Items 2/3 each add one value here.
- `friends/AvatarTextureCache.java` (per platform module) — existing
  Steam-image-handle-to-Minecraft-texture conversion, reused as-is by Item 4
  (FR-BB4.3) and, if FR-BB3.3's icon path is in scope, extended/mirrored for
  Item 3's achievement icons.

### `features/server-join-presence` (already shipped, consumed not modified except one API addition)
- `api/src/main/java/de/lazuli/api/serverjoinpresence/FriendServerPresenceReader.java`
  — currently one method, `int friendsOnServer(String hostPort)`
  (`implementation-plan.md` "Files to Create", `api` module section).
  Two implementers exist today: `ServerPresenceScanner` (real,
  `features/server-join-presence/services/`) and a `Noop*` counterpart
  (`NoopFriendServerPresenceReader`) — both must gain the new accessor in the
  same change (spec Compatibility, FR-BB4.2's backward-compatibility
  concern).
- `ServerPresenceScanner` already maintains `Map<String, Set<Long>>
  friendsByServer` internally (`implementation-plan.md` Decision 4) — the
  identity data FR-BB4.2 option (a) needs already exists in memory, this is a
  pure accessor addition, no new scan logic.
- `platform/fabric-<version>/.../ServerJoinPresenceBridgeHandoff.java` —
  existing per-module hand-off publishing `FriendServerPresenceReader`;
  `MainMenuClientInitializer` obtains it here (Architecture, no new bridge
  class needed for Item 4).

### `services/steamworks-inventory-bindings` fork (Item 3's prerequisite question)
- `gradle.properties:51` — `steamworks4j_version=v1.10.0-inventory.1`, this
  repo's actually-resolved fork jar coordinate (confirmed by direct read).
- `services/steamworks-inventory-bindings/specification.md` — confirms the
  fork's known-wrapped-interface list (`SteamRemoteStorage`, `SteamFriends`,
  `SteamUGC`, `SteamMatchmaking`/`SteamMatchmakingServers`, `SteamApps`,
  `SteamUser`, `SteamUtils`) plus the added `SteamInventory` — does **not**
  list `SteamUserStats` as confirmed-wrapped or confirmed-absent; FR-BB3.1's
  `javap` pass is a genuinely open question, not answerable from any file
  already read in this repo.
- `services/src/main/java/de/lazuli/services/steamworks/` — existing
  one-gateway-per-interface convention (e.g. `SteamworksSteamFriendsGateway`)
  that `SteamAchievementsGateway` (FR-BB3.2a) or a fork-added binding
  (FR-BB3.2b) would follow.

## Decisions

### 1. Item 1 — dock flip + global render, single shared instance (FR-BB1.1-1.7)
- `FriendSidebarWidget`'s three right-edge sites flip to `x = 0`
  (`handleX()`, hit-test `testX`, `setX(...)`) — purely mechanical, applied
  identically across all three platform modules.
- `MainMenuScreen.reservedWidth()`/`panelX()`/`panelWidth()` re-derived: the
  sidebar's reserved column moves to the left (`0` through
  `sidebarCollapsedWidth() + LEFT_MARGIN`), the tab bar keeps its existing
  right-edge dock (`barX = width - TAB_BAR_WIDTH`) independently — the two are
  no longer arithmetically coupled. `panelWidth()` becomes "subtract sidebar +
  left margin from the left, tab bar + right margin from the right."
  `panelY()`/`panelHeight()` untouched (horizontal-axis-only change, FR-BB1.2).
- New `HudRenderCallback.HUD_RENDER_CALLBACK` registration, one per platform
  module's composition root (`FabricFriendsSidebarInjector` or a new sibling
  class in the same `friends/` package — this plan recommends keeping it in
  `FabricFriendsSidebarInjector` itself, since that class already owns the
  sidebar's lifecycle wiring, rather than splitting sidebar-lifecycle logic
  across two classes). The callback early-returns whenever
  `Minecraft.getInstance().screen != null` (FR-BB1.4), letting the existing
  `ScreenEvents.AFTER_INIT`-driven path own that frame instead — the two
  triggers are mutually exclusive by construction.
- One long-lived `FriendSidebarWidget` instance constructed at composition-root
  time (not per-`Screen`), shared by both the `HudRenderCallback` path and the
  (now unconditional) `ScreenEvents.AFTER_INIT` path — satisfies FR-BB1.4's
  "single shared instance, not two" requirement and the Escape/PauseScreen
  no-flicker requirement (same object, same state, across the transition).
- `MainMenuScreen` keeps its own separate, dedicated `FriendSidebarWidget`
  instance as today (FR-BB1.7's stated default) — lowest risk, no
  duplication problem identified in this planning pass (the main menu is
  never simultaneously "no screen open," so the two instances never compete
  for the same frame).
- `isAllowListed(Screen)` (`:91-98`) is deleted; `onScreenInit` attaches
  unconditionally (FR-BB1.5). A small explicit deny-list is added only for
  screens with a concretely identified conflict — this planning pass did not
  find one by reading the repo (no full-screen modal/password-prompt
  `Screen` class was located that would obviously conflict), so the deny-list
  starts **empty**, structured as a `Set<Class<? extends Screen>>` constant
  so implementation can add an entry if manual verification (Test Strategy)
  surfaces a real collision — this is a planning-phase judgment call, flagged
  for the user's visibility per this repo's "don't silently decide a behavior
  change" convention.
- **Raw mouse-input read path (FR-BB1.3, the batch's single largest risk,
  Risk 1)**: not `javap`-confirmed this pass (no decompiler/build tool
  available in this planning session). The intended shape —
  `Minecraft.getInstance().mouseHandler` exposing current
  x/y/button-down state, polled once per `HudRenderCallback` firing rather
  than dispatched via `Screen.mouseClicked` — is Fabric/Minecraft's
  well-known convention, but must be independently confirmed per platform
  module (Yarn vs. Mojang mapping divergence expected) before
  implementation, per this repo's standing discipline for exactly this class
  of unknown (mirrors `server-join-presence`'s own Risk 1 precedent).

### 2. Item 4 — servers-panel friend avatars (FR-BB4.1-4.5)
- `FriendServerPresenceReader` (in `features/server-join-presence`'s `api`
  module) gains one new method:
  `List<Long> friendSteamIdsOnServer(String hostPort)` — a default method
  returning `List.of()` is **not** used (this repo's convention is explicit
  `Noop*` implementer classes, not default-method fallbacks, per every
  sibling interface in this repo); instead both existing implementers
  (`ServerPresenceScanner`, `NoopFriendServerPresenceReader`) are updated in
  the same change — `ServerPresenceScanner` returns
  `List.copyOf(friendsByServer.getOrDefault(normalize(hostPort),
  Set.of()))` (reusing its already-computed cache, Decision/Existing
  Implementation), `NoopFriendServerPresenceReader` returns `List.of()`. This
  is the sole cross-feature `api` surface change this batch introduces
  (spec Public API item 4), scoped as a small, backward-compatible amendment
  to `features/server-join-presence/specification.md` (a new FR, e.g.
  FR3.2a) sequenced ahead of the main-menu consumer.
- `ServersPanel` obtains its existing `FriendServerPresenceReader` handle
  (already wired per FR-BB4.1, via `MainMenuClientInitializer`'s composition
  root using the existing `ServerJoinPresenceBridgeHandoff`) and, per row,
  calls `friendsOnServer(hostPort)` for the count and
  `friendSteamIdsOnServer(hostPort)` for identities, rendering up to 2
  avatars via `AvatarTextureCache` (reused, no new texture-loading code) plus
  a "+N" badge (`N = count - 2`) when `count > 2` (FR-BB4.4). If the
  identity list's size doesn't match the count (a benign race between the
  two reads, e.g. scanner ticked between them), the badge computation always
  uses the **count**, never `identities.size()`, per FR-BB4.4's explicit
  "count stays authoritative" requirement — the identity list only supplies
  which specific avatars to draw for however many of the first 2 slots it
  can fill.
- Avatar placement: adjacent to each row's existing ping-status
  dot/player-count area (Saved and Browser sub-views); exact pixel offset a
  per-panel manual-verification-driven decision at implementation time,
  consistent with `specification-post-launch-fixes-3.md`'s own "manual
  per-panel discrepancy fix" precedent this spec cites (FR-BB4.3).

### 3. Item 2 — Home/Activity tab (FR-BB2.1-2.5)
- New `MainMenuTab.HOME` enum value.
- New `HomePanel` (or `ActivityPanel`) class, structurally mirroring an
  existing simple list-panel (closest existing precedent: `ServersPanel`'s
  saved-list rendering loop, minus the connect/context-menu-specific
  chrome) — filters `FriendsSidebarFacade.friends()` to
  `gameAppId == thisMod'sOwnAppId`, sorts via the existing
  `FriendSidebarStateMachine.sortForDisplay` (reused, not reimplemented),
  renders avatar/name/Rich-Presence-status per row, empty state when the
  filtered list is empty (FR-BB2.2, matching `ServersPanel`'s own
  empty-state convention). Row click reuses `FriendContextMenuWidget`
  as-is (FR-BB2.4) — no new interaction model.
- No new `services`/`api` cross-feature edge — pure presentation over
  already-composed `FriendsSidebarFacade` data (Architecture).

### 4. Item 3 — Achievements tab, gated on FR-BB3.1's `javap` verification
- **Mandatory first step, before any other Item-3 work**: locate the
  resolved `steamworks4j` jar (Gradle dependency cache entry for
  `com.code-disaster.steamworks4j:steamworks4j:v1.10.0-inventory.1`, or the
  Jar-in-Jar-included copy under a platform module's build output) and run
  `javap -p` (or direct source enumeration if the fork's source is available
  locally, mirroring how `services/steamworks-inventory-bindings/specification.md`
  itself was produced) against it to confirm whether `SteamUserStats`/
  `SteamUserStatsNative` exist, and if so, which of `GetNumAchievements`,
  `GetAchievementName`, `GetAchievementAndUnlockTime`,
  `GetAchievementDisplayAttribute`, `GetAchievementIcon`,
  `RequestCurrentStats` are present.
- **Branch A — already wrapped (FR-BB3.2a)**: build
  `SteamAchievementsGateway`/`SteamworksSteamAchievementsGateway` in
  `services/src/main/java/de/lazuli/services/steamworks/` (one-gateway-per-
  interface convention), calling `RequestCurrentStats` once per Achievements-
  tab open and awaiting `UserStatsReceived_t` before any accessor read
  (Networking, standard Valve requirement); a `NoopSteamAchievementsGateway`
  (`List.of()`) for the Steam-unavailable case (FR-BB3.6). Then implement the
  tab (below) in the same pass.
- **Branch B — not wrapped (FR-BB3.2b)**: this becomes its own standalone
  prerequisite plan/pass, structurally identical to
  `services/steamworks-inventory-bindings/specification.md`'s own scoping
  (new `SteamUserStats.java`/`SteamUserStatsNative.java` file pair, JNI glue,
  native rebuild, same rebase-friendliness/CI caveats) — **this plan does not
  itself design that fork change**; if FR-BB3.1 resolves to Branch B, the
  Achievements tab is blocked on a separate, explicitly-sequenced
  infrastructure pass (its own specification + implementation plan, following
  the Inventory-bindings precedent exactly, including a fresh CI/native-build
  risk review) before any UI work proceeds, mirroring how "no other work...
  proceeds until this fork is ready" was applied to the Store panel. This
  plan's own Acceptance Criteria (below) treat correctly identifying which
  branch applies, and stopping at that boundary if Branch B, as a complete
  and successful outcome of this planning pass for Item 3 — it does not
  presume Branch A.
- Icon handling (FR-BB3.3): if `GetAchievementIcon` is confirmed present
  (same `javap` pass), reuse/extend `AvatarTextureCache`'s existing
  image-handle-to-texture conversion pattern; if it's a materially larger
  gap than the text fields (e.g. present in Branch A's confirmed set but the
  icon conversion needs new code, or absent entirely), fall back to a
  generic placeholder icon (text-only display) for v1 — an accepted scope
  reduction, not a blocker to shipping the rest of the tab.
- `MainMenuTab.ACHIEVEMENTS` new enum value; panel is a scrollable grid/list,
  each entry icon-or-placeholder/name/description/locked-unlocked
  state/unlock date (FR-BB3.4), hidden achievements withheld until unlocked
  per the `"hidden"` display attribute (FR-BB3.5), Steam-unavailable fallback
  matching base spec FR7.5/FR4.6's existing convention (FR-BB3.6).
- Achievement data fetched once per tab-open/cached for the screen session
  (matching FR1.3's existing "state resets each fresh screen open"
  convention), not re-queried every frame (Performance).

## Files to Create

### Item 1
- No new files if `HudRenderCallback` registration is added directly inside
  the existing `FabricFriendsSidebarInjector.java` (this plan's recommended
  default, Decision 1). If implementation instead finds a concrete reason to
  split it out, a new sibling class (e.g.
  `friends/GlobalFriendSidebarOverlay.java`) per platform module is the
  fallback shape — flagged as an implementation-time judgment call, not
  fixed here.

### Item 2
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/HomePanel.java`
  (×3 platform modules).

### Item 3 (Branch A only; Branch B adds its own separate fork-repo files, out
of scope for this plan's own file list)
- `api/src/main/java/de/lazuli/api/mainmenu/AchievementSummary.java` — record
  per spec Public API item 3.
- `services/src/main/java/de/lazuli/services/steamworks/SteamAchievementsGateway.java`
  (interface) and `SteamworksSteamAchievementsGateway.java` (real impl).
- `services/src/main/java/de/lazuli/services/steamworks/NoopSteamAchievementsGateway.java`.
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/AchievementsPanel.java`
  (×3 platform modules).

### Item 4
- No new files — `friendSteamIdsOnServer` is an addition to an existing
  interface/existing implementer classes (Files to Modify), and `ServersPanel`
  is modified in place, not split into a new class.

## Files to Modify

### Item 1 (×3 platform modules)
- `friends/FriendSidebarWidget.java` — dock-flip (Decision 1).
- `friends/FabricFriendsSidebarInjector.java` — remove/replace
  `isAllowListed`, add empty deny-list constant, add `HudRenderCallback`
  registration, promote sidebar instance construction to composition-root
  time (not per-`Screen`).
- `mainmenu/MainMenuScreen.java` — `panelX()`/`reservedWidth()`/`panelWidth()`
  re-derivation, both `barX` call sites (`mouseClicked`/`mouseScrolled` and
  render).

### Item 2 (×3 platform modules)
- `mainmenu/MainMenuScreen.java` — tab-bar wiring for the new `HOME` tab
  (same pattern as the existing four tabs' wiring).
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — add `HOME`.

### Item 3 (×3 platform modules, Branch A)
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — add
  `ACHIEVEMENTS`.
- `mainmenu/MainMenuScreen.java` — tab-bar wiring for the new tab.
- `mainmenu/MainMenuClientInitializer.java` (or wherever the composition
  root currently constructs `FriendsSidebarFacade`/other gateway handoffs —
  exact file name a repo-check at implementation time) — construct/obtain
  `SteamAchievementsGateway`, hand off to `AchievementsPanel`.

### Item 4
- `features/server-join-presence/api/.../FriendServerPresenceReader.java` —
  add `List<Long> friendSteamIdsOnServer(String hostPort)`.
- `features/server-join-presence/services/ServerPresenceScanner.java` —
  implement the new method from its existing cache.
- `features/server-join-presence/services/NoopFriendServerPresenceReader.java`
  — implement returning `List.of()`.
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/ServersPanel.java`
  (×3) — per-row avatar/badge rendering.
- `features/server-join-presence/specification.md` — small amendment adding
  the new accessor as its own FR (e.g. FR3.2a), per this plan's Decision 2
  (documented, not silently added).

## Interfaces
- `api/.../mainmenu/MainMenuTab` — gains `HOME`, `ACHIEVEMENTS`.
- `api/.../mainmenu/AchievementSummary` — new record (Item 3, Branch A).
- `features/server-join-presence/api/.../FriendServerPresenceReader` — gains
  `friendSteamIdsOnServer(String)` (Item 4).
- `services/.../SteamAchievementsGateway` — new interface (Item 3, Branch A).

## Services
- New: `SteamAchievementsGateway`/`SteamworksSteamAchievementsGateway`/
  `NoopSteamAchievementsGateway` (Item 3, Branch A only) — a fourth
  Steamworks-interface gateway following the existing convention.
- No new service for Items 1/2/4 — all reuse already-existing
  facades/gateways.

## Test Strategy
Per this repo's standing convention (`features/main-menu/specification.md`
UI section; `implementation-plan-post-launch-fixes-3.md` Test Strategy) —
GUI/rendering behaviors are manually verified in-game, per platform module;
plain-JVM-testable pieces get unit tests.

1. **Unit tests (plain JVM)**:
   - `ServerPresenceScannerTest` extended with a case asserting
     `friendSteamIdsOnServer(hostPort)` returns exactly the expected friend
     IDs for a fake gateway's decoded values (Item 4).
   - If Item 3 Branch A: a fake-`SteamAchievementsGateway`-backed test of
     whatever pure logic exists (e.g. hidden-achievement placeholder
     substitution), mirroring this repo's existing gateway-adjacent test
     shape where the gateway itself isn't unit-testable (native calls) but
     surrounding logic is.
2. **Compilation across all three platform modules** — `gradlew build` must
   succeed with every new `api` method, new enum values, and (Item 4) the
   updated `FriendServerPresenceReader` implementers in place.
3. **Manual in-game verification, per platform module** (all three):
   - Item 1: sidebar renders at the left edge on all six previously-
     allow-listed screens, `MainMenuScreen`, every other menu screen, and
     during ordinary gameplay with no `Screen` open; hover/click/scroll work
     with no `Screen` open (raw-input path, Risk 1); Escape transition
     to `PauseScreen` shows no flicker/state reset; check each screen for
     left-edge visual collisions with vanilla content (`PauseScreen`'s
     button column, `OptionsScreen` layout) not previously a concern at the
     right edge; verify no double-render in any frame.
   - Item 2: Home tab lists friends playing this game with correct
     avatar/name/status; empty state when none; row click opens the
     existing context menu correctly.
   - Item 3: Achievements tab (if Branch A) shows every achievement with
     correct locked/unlocked visual distinction, unlock dates where
     applicable, hidden achievements withheld until unlocked, Steam-
     unavailable fallback message when Steam is off.
   - Item 4: Servers panel rows (Saved and Browser) show up to 2 friend
     avatars plus a correct "+N" badge for a server with >2 friends present;
     no visual crowding of the existing ping-dot/player-count/lock-icon
     elements.
   - All new fill/text color literals across all four items carry full
     `0xFF` alpha (spec's standing caution).

## Dependencies
- **No new external Maven/Gradle dependency for Items 1, 2, 4.**
  `net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback` is part of
  the already-declared `fabric-api` dependency (`fabric.mod.json`'s
  `"fabric-api": "*"`, same as `server-join-presence`'s own precedent for
  `ClientPlayConnectionEvents`) — no version bump, no new Maven coordinate.
- **Item 3, Branch A**: no new external dependency — `SteamUserStats` is
  part of the already-resolved `steamworks4j` fork jar
  (`gradle.properties:51`, `v1.10.0-inventory.1`), consumed via the existing
  Jar-in-Jar mechanism.
- **Item 3, Branch B**: a new fork build/version bump of
  `com.code-disaster.steamworks4j:steamworks4j` (a new tag on this repo's
  own fork, not a public Maven Central coordinate — same as the existing
  `v1.10.0-inventory.1` tag) — this is an internal fork release, not a
  registry-verifiable external dependency, so the "verify via
  search.maven.org" requirement does not apply (this coordinate is not, and
  will not be, published to Maven Central); its existence and version string
  are instead confirmed by this repo's own `gradle.properties` after the
  prerequisite fork pass lands, exactly like `v1.10.0-inventory.1` was.
- **New internal (inter-module) dependency edge**: none beyond what already
  exists — `features/main-menu`'s platform modules already depend on
  `features:server-join-presence` (Item 4) and on `services`/`api` (Items
  2/3) via existing `project(...)` edges from prior work.

## Risks
1. **FR-BB1.3's raw mouse-input read path is not `javap`-confirmed this
   planning pass** (no decompiler tool available) — the single largest
   concrete unknown in this batch, carried forward as implementation's
   mandatory first step for Item 1, per this repo's established discipline
   for this exact class of unknown (mirrors `server-join-presence`'s own
   Risk 1).
2. **FR-BB1.5's "all screens" allow-list removal may surface an undiscovered
   conflicting `Screen`** (e.g. a full-screen modal this planning pass didn't
   locate by reading the repo) — the empty-deny-list default (Decision 1) is
   a bet that no such conflict exists; manual verification (Test Strategy)
   is the actual safety net, not static analysis.
3. **FR-BB3.1's outcome (Branch A vs. B) is unknown until the `javap` pass
   runs** — this plan cannot commit to a single-pass Item-3 timeline; if
   Branch B, Item 3 is materially larger (a new fork/native-rebuild pass)
   and should be re-planned as its own specification once confirmed, not
   forced into this plan's existing shape.
4. **FR-BB4.2's identity-list/count race** (Decision 2) — `friendsOnServer`
   and `friendSteamIdsOnServer` are two separate reads of a ticking cache;
   a benign one-tick mismatch is possible. Mitigated by always trusting the
   count for the "+N" badge math (FR-BB4.4), never the identity list's own
   size — implementation must not silently switch to
   `identities.size()`-based badge math for convenience.
5. **Editing `FriendSidebarWidget`/`FabricFriendsSidebarInjector`/
   `MainMenuScreen`** — already-shipped, already-verified code with no
   automated regression coverage for rendering/hit-test behavior (same risk
   class flagged in `implementation-plan-post-launch-fixes-3.md` Risks) —
   verified only by manual in-game testing, not unit tests.
6. **Cross-platform drift** — all three platform modules must receive
   identical fixes; fabric-1.21.11's known line-offset from the other two
   modules (confirmed in prior plans) means implementation must re-derive
   exact line numbers per module rather than copy-pasting a single diff
   verbatim.

## Acceptance Criteria
Mapped to the specification's functional requirements:

- **FR-BB1.1-1.2** — Code review: all three dock-flip sites in
  `FriendSidebarWidget` and `MainMenuScreen`'s re-derived layout methods
  compile and are visually confirmed left-docked/correctly-spaced on all
  three platform modules.
- **FR-BB1.3-1.4** — `.claude/context/minecraft.md` gains a confirmed row for
  the raw-mouse-input API surface per platform module (Risk 1) before this
  criterion is met; manual verification confirms hover/click/scroll work
  with no `Screen` open and the Escape transition is flicker-free.
- **FR-BB1.5-1.7** — Code review confirms `isAllowListed` is removed/replaced
  by the (initially empty) deny-list constant; manual per-screen pass finds
  no visual collision (or the deny-list gains a documented entry if one is
  found); `MainMenuScreen` retains its own dedicated sidebar instance.
- **FR-BB2.1-2.5** — Manual verification confirms the Home tab's filter,
  sort, empty state, and context-menu reuse all behave per spec.
- **FR-BB3.1** — This plan's own success criterion for Item 3 includes: the
  `javap`/source-enumeration step is actually run and its result (Branch A
  or B) is documented, even if Branch B means the rest of Item 3 is
  re-scoped as a separate pass.
- **FR-BB3.2a/3.2b-3.6** (Branch A only, this pass) — `SteamAchievementsGateway`
  compiles and returns real data against a live Steam session; Achievements
  tab shows correct locked/unlocked/hidden states and unlock dates; Steam-
  unavailable fallback confirmed.
- **FR-BB4.1-4.5** — `ServerPresenceScannerTest`'s new case passes;
  `friendSteamIdsOnServer` compiles against both implementers; manual
  verification confirms avatar/badge rendering and the count-authoritative
  badge math (Decision 2/Risk 4) on both Saved and Browser rows, all three
  platform modules.
- **Compatibility** — `gradlew build` succeeds on all three platform modules
  with every new/changed file in place.
- **Explicitly out of scope for this workflow's own sign-off**: any claim
  that a real friend's Steam client actually invites/joins correctly beyond
  what already-shipped `server-join-presence`/`steam-world-hosting` connect
  flows already establish — this batch adds no new connect mechanism.

## Open Questions
- None blocking this plan's own sign-off. FR-BB1.5's deny-list contents and
  FR-BB3.1's branch outcome are implementation-phase discoveries this plan
  explicitly defers to (per the spec's own framing), not unresolved design
  ambiguities needing user input before implementation can start.
