# Implementation Plan — Main Menu ("Stonebound")

## Summary
Build `features/main-menu` (all requirement groups FR0–FR8) as this repo's
first full `TitleScreen` replacement, composing (at the platform composition
root, never via a Feature→Feature import) `features/friends-sidebar`'s
existing facade/state-machine and `features/server-browser`'s existing
session/table-model logic inside one new `Screen`, behind a from-scratch
perspective 3D background. FR0 (deleting `features/hello-world-main-menu`)
is executed first as a literal prerequisite, not a follow-up.

**Material update to the specification's own Store Panel (FR5) and
Compatibility sections, made explicit here rather than silently
reinterpreted:** the specification's FR5/Compatibility text describes
steamworks4j as having no `ISteamInventory`/`ISteamMicrotransaction` binding
and models Store ownership as DLC-App-ID entitlement
(`SteamApps.isSubscribedApp`/`isDlcInstalled`). That premise is now stale —
`services/steamworks-inventory-bindings/plan.md`'s "Final Status" (dated
2026-07-22, same day as this plan) confirms a forked, real
`SteamInventory`/`SteamInventoryNative`/`SteamInventoryCallback`/
`SteamInventoryResult`/`SteamItemDetails` binding is published
(`com.github.Probastian.steamworks4j:steamworks4j:v1.10.0-inventory.1`, via
JitPack) and this mod's `gradle.properties`/root `build.gradle`/
`services/build.gradle`/all three platform `build.gradle`s are **already**
repointed at it, building green. This plan therefore designs the Store panel
against the fork's real `SteamInventory` API (ownership = item-instance
possession via `GetAllItems`/`GetItemsByID`, purchase = `StartPurchase`) as
the *primary* path, keeping a DLC-App-ID secondary path only as a documented
fallback for a catalog item that has no configured inventory-item-definition
ID yet (mirroring the spec's own `steamDlcAppId: null` "not ownable yet"
pattern, generalized to an `inventoryItemDefId: null` equivalent). See
Decision 1 for the full reasoning and the resulting catalog schema change.
This also resolves one item the specification left open (FR5.3): a direct
`WebFetch` read of the fork's `SteamFriends.java` (Files to
Create/reference below) confirms
`public void activateGameOverlayToStore(int appID, OverlayToStoreFlag flag)`
**exists** on this exact steamworks4j fork version — no `steam://store/<appid>`
URI fallback is needed for the "View in Store"/overlay affordance this plan
still keeps as a secondary, non-primary action (Decision 1).

No implementation code is written as part of this plan.

## Existing Implementation

### Hello World Main Menu removal (FR0) — re-verified directly, not assumed from the spec's own line numbers
- `features/hello-world-main-menu/` — full module: `api`(none)/`config`/`services`
  classes (`HelloWorldMainMenuConfig.java`, `HelloWorldMainMenuConfigIO.java`,
  `HelloWorldMainMenuService.java`), `README.md`, `implementation-plan.md`
  (this repo's older per-feature plans predate the `plan.md` naming
  convention `server-browser` already established — this feature's own plan
  keeps the newer `plan.md` name per the task's own instruction),
  `specification.md`. No `verification-report.md` currently present in this
  module (glob confirmed) — FR0.1's wording anticipates one but none exists
  to delete.
- **`settings.gradle:23`** — `include 'features:hello-world-main-menu'`
  (confirmed, exact line).
- **Platform `build.gradle` dependency lines — re-verified per module, not
  assumed identical line numbers**: `platform/fabric-26.2/build.gradle:15`,
  `platform/fabric-26.1/build.gradle:15`, `platform/fabric-1.21.11/build.gradle:15`
  — all three carry `implementation project(':features:hello-world-main-menu')`
  at the **same** line 15 (confirmed by direct grep of all three files this
  pass, not assumed from the spec's 26.2-only citation).
- **`HelloWorldMainMenuClientInitializer` — confirmed single-purpose, no
  double-duty entrypoint** (resolves FR0.4's "confirm before deleting
  wholesale" caution): read in full,
  `platform/fabric-26.2/src/main/java/de/lazuli/HelloWorldMainMenuClientInitializer.java`
  does exactly one thing — construct `FabricMainMenuHook`/
  `HelloWorldMainMenuConfigIO`/`HelloWorldMainMenuService` and call
  `service.applyToMainMenu()`. It is a standalone `ClientModInitializer`, not
  folded into another entrypoint class. Present at the same relative path in
  all three platform modules (per `fabric.mod.json`'s `"client"` array,
  below) — delete all three.
- **`fabric.mod.json`'s `"client"` entrypoint array** — confirmed present at
  `platform/fabric-26.2/src/main/resources/fabric.mod.json:22`
  (`"de.lazuli.HelloWorldMainMenuClientInitializer"`, first entry in the
  array) — remove that one line per module; the other six entries
  (`SteamworksClientInitializer`, `SteamWorldHostingClientInitializer`,
  `SteamCloudSyncClientInitializer`, `RichPresenceClientInitializer`,
  `FriendsSidebarClientInitializer`, `ServerBrowserClientInitializer`) are
  untouched by FR0, but this feature's own new
  `MainMenuClientInitializer` entry (Files to Create) is added to this same
  array as part of this plan's own work, ordered **last** (after
  `ServerBrowserClientInitializer`), matching this repo's own
  "position not load-bearing beyond after-Steamworks" convention
  (`server-browser` plan, Files to Modify) — except this feature's ordering
  *is* load-bearing in one respect: `MainMenuClientInitializer` must run
  after `FriendsSidebarClientInitializer`/`ServerBrowserClientInitializer`
  since it obtains their already-constructed facade/factory instances at
  startup (Architecture, spec) — placing it last in the array satisfies this.
- **A second, not-in-the-spec deletion this plan adds (important catch)**:
  `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/FabricMainMenuHook.java`
  (one per platform module, confirmed present in all three by direct read of
  the 26.2 copy) is hello-world-main-menu's own *Version Adapter* class,
  implementing `api/.../mainmenu/MainMenuHook` via a
  `ScreenEvents.AFTER_INIT`/`Screens.getWidgets` label-injection Pattern-1
  hook — it lives under package `de.lazuli.mainmenu` in **each platform
  module**, not under the feature module, so FR0.1's "delete the feature
  module in its entirety" does **not** by itself delete it. **This package
  path (`de.lazuli.mainmenu`) is also the natural home for this feature's own
  new platform classes** (`MainMenuScreen`, `MainMenuBackgroundRenderer`,
  etc., Public API item 3) — so this plan's FR0 task must delete
  `FabricMainMenuHook.java` (all three copies) explicitly, not rely on
  feature-module deletion catching it, before this feature's own classes are
  added to the same package/directory.
- **A third, not-in-the-spec deletion this plan adds**:
  `api/src/main/java/de/lazuli/api/mainmenu/MainMenuHook.java` — a top-level
  `api`-module interface (confirmed present, read in full), the "Platform
  API" half of hello-world-main-menu's Pattern-1 hook. It is **not** inside
  `features/hello-world-main-menu/` (so FR0.1's directory-deletion wording
  does not cover it either) and it sits at the **exact package path**
  (`de.lazuli.api.mainmenu`) this feature's own new API types (`MainMenuTab`,
  `StoreItem`, `WardrobeSlot`, `CharacterPose`, spec Public API item 1) must
  be added to. This plan's FR0 task deletes `MainMenuHook.java` outright
  (nothing in `api`, `services`, or any platform module references it once
  the platform's `FabricMainMenuHook`/`HelloWorldMainMenuClientInitializer`
  are also deleted — confirmed via grep, only 3 platform files + this one
  `api` file reference the `MainMenuHook` type) before this feature's own new
  files are added to the now-empty `de.lazuli.api.mainmenu` package.
- **CORRECTION (found during implementation, not by this planning pass — the
  claim below was wrong):** `platform/fabric-<version>/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`
  (all three modules) imports `HelloWorldMainMenuConfigIO` and defines a
  private nested `HelloWorldMainMenuCloudSyncAdapter implements CloudSyncable`
  (registered in that file's `cloudSyncables` list) that cloud-syncs
  `hello-world-main-menu.json` via `CloudSyncCoordinator`. This is a real,
  functioning dependency FR0's original grep missed. **Decision, made during
  implementation**: since `hello-world-main-menu` is leftover boilerplate with
  no real user-facing state worth preserving, delete
  `HelloWorldMainMenuCloudSyncAdapter` outright and replace its
  `cloudSyncables` list entry with `List.of()` (no replacement syncable) in
  all three `SteamCloudSyncClientInitializer.java` files, removing the
  `HelloWorldMainMenuConfigIO` import and the now-unused
  `helloWorldConfigPath` local. This is now added to Files to Modify (FR0)
  below. **No other reference to `hello-world-main-menu` anywhere else in the
  repo** beyond this one now-corrected omission — re-confirmed via repo-wide
  grep for `hello-world-main-menu`/`HelloWorldMainMenu`/`MainMenuHook`/
  `FabricMainMenuHook` after this fix. FR0.5's "no dangling reference after
  rebuild" bar is met once all of the above (including this correction) are
  removed.

### Friends Sidebar reuse (FR7) — signatures spot-confirmed
`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`
confirmed (direct read/grep) to expose every accessor FR7.1–FR7.5 cite by
name: `isSteamAvailable()`, `steamUnavailableMessage()`, `friends()`,
`localProfile()`, `richPresenceStatus(long)`, `actions()` (returns
`FriendsDataSource`), `joinPolicy()`, `selectJoinPolicy(JoinPolicy)`. No gap —
matches the specification's own Architecture section exactly. This plan does
not re-verify `FriendSidebarStateMachine.isExpanded`/`sortForDisplay`/
`statusLabel` beyond the spec's own citations (already precise
line-numbered), since the facade-level surface above is what this feature's
platform code actually calls.

### Server Browser reuse (FR4) — `ServerBrowserSession` read in full
`api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserSession.java`
(read in full this pass, resolving the spec Architecture's own flagged gap):
confirmed accessor methods this feature's Browser sub-view needs:
`start(ServerBrowserSource, Consumer<List<ServerBrowserRow>>, Runnable)`,
`refresh()`, `isRefreshing()`, `setSortColumn(ServerBrowserColumn)`,
`setFilter(ServerBrowserFilterState)`, `currentRows()`, `sortColumn()`,
`sortAscending()`, `close()`. `ServerBrowserSessionFactory.newSession()`
(same package) constructs a fresh session per screen-open — this feature's
Servers-panel Browser sub-view calls `newSession()` when the sub-view
becomes active and `close()` when it is left or the screen closes
(Architecture, spec). `ServerBrowserFilterState`'s fields (`searchText`,
`maxPing`, `hideFull`, `hidePasswordProtected`) and `ServerBrowserColumn`'s
six values are both already top-level `api` types, directly usable —
confirmed present at
`api/src/main/java/de/lazuli/api/serverbrowser/{ServerBrowserFilterState,ServerBrowserColumn}.java`.
No gap; this feature's `services` layer depends on `api/.../serverbrowser`
directly, same layering the specification's own Architecture section
already sanctions.

### Steamworks Inventory fork — confirmed live and building (see Summary)
`services/steamworks-inventory-bindings/plan.md`'s "Final Status": fork tag
`v1.10.0-inventory.1`, JitPack coordinate
`com.github.Probastian.steamworks4j:steamworks4j:v1.10.0-inventory.1`, this
mod's own Gradle graph already repointed and green
(`gradlew build` succeeds all 3 platform modules). Confirmed via this pass's
own `WebFetch` of the fork's `SteamFriends.java` source (tag
`v1.10.0-inventory.1`): `activateGameOverlayToUser`,
`activateGameOverlayToStore(int appID, OverlayToStoreFlag flag)`,
`activateGameOverlayToWebPage`, `activateGameOverlayInviteDialog` all exist —
resolves the spec's own flagged-open FR5.3 question with no fallback needed.
**Known, carried-forward limitation (not fixable by this plan): only Windows
x64 natives exist for this fork** (Linux/macOS builds explicitly deferred,
tracked gap in the fork's own README per that plan's Final Status) — flagged
below as Risk 2 and reflected in this plan's own Acceptance Criteria/Test
Strategy (Store panel functionality involving real `SteamInventory` calls is
Windows-only until a follow-up fork release adds the other three targets).
Also carried forward: **no real Steam App ID has configured Inventory items
yet** (that plan's own Risk 4) — this plan's own Store panel acceptance bar
is therefore capped at "builds against the real API, degrades honestly when
no items are configured," not "verified real ownership/purchase," exactly
mirroring the fork initiative's own honest test-strategy ceiling.

### `SteamworksSteamFriendsGateway` precedent (FR5.3's overlay-activation shape)
`services/src/main/java/de/lazuli/services/steamworks/SteamworksSteamFriendsGateway.java:290-298`
(confirmed present per spec Architecture) already wraps
`activateGameOverlayToUser` behind a small gateway method — this plan's
`MainMenuStoreOwnershipChecker`/overlay-activation code (Decision 1) follows
this same "one gateway class wraps the steamworks4j call, feature code never
imports `com.codedisaster.steamworks.*` directly" discipline, consistent with
`server-browser` NFR3's precedent this repo already established.

### Idle-character/3D-background precedent — genuinely none (spec Architecture)
Confirmed: no feature in this repo renders a perspective 3D scene from inside
a `Screen` today. `WorldRestoreScreen`
(`platform/fabric-*/.../cloudsync/WorldRestoreScreen.java`) is the closest
from-scratch custom-`Screen` precedent but is 2D-only. Vanilla's own
`TitleScreen`/`PanoramaRenderer` is the nearest in-engine analog for camera/
projection setup inside a `Screen`'s render cycle — first implementation
task for the background renderer is reading that vanilla class per target
version (Sequencing, Risk 1).

### Design source
`design_handoff_main_menu/README.md` read in full — authoritative for exact
layout/color/interaction values (Design Tokens, per-panel prose); the sibling
`.dc.html` file is not read by this plan (non-literal reference, per spec
Overview) and is left for implementation to consult only for exact per-item
sample values not stated in the README prose, as the spec itself directs.

## Decisions on Items the Specification Left to Planning

### 1. Store Panel ownership/purchase model — real `SteamInventory` primary path, DLC-App-ID fallback retained, catalog schema updated
Per the Summary's update: `StoreItem` (spec Public API item 1) gains an
`inventoryItemDefId` field (nullable `OptionalInt`), kept **alongside**
`steamDlcAppId` (also nullable) rather than replacing it outright, so a
catalog entry may be configured either way (or neither, meaning "not
purchasable via Steam yet," the same `null`-means-not-ownable convention the
spec's own FR5.2/Configuration already establish, generalized to two
possible identifier fields instead of one):
```java
record StoreItem(String id, String displayName, String description, String category,
                  int priceCents, OptionalInt originalPriceCents,
                  OptionalInt inventoryItemDefId, OptionalInt steamDlcAppId,
                  boolean featured) {}
```
`services/main-menu/OwnershipChecker` (spec Public API item 2, unchanged
shape — `boolean isOwned(StoreItem item)`) is implemented platform-side by
**two** Version Adapter classes composed by a small priority chain (first
try inventory-item-def lookup if `inventoryItemDefId` is present, else fall
back to DLC entitlement if `steamDlcAppId` is present, else `false` and a
"not purchasable yet" placeholder render per FR5.2's own existing wording):
- `MainMenuStoreOwnershipChecker` (real path) — wraps the fork's
  `SteamInventory.getAllItems()`/`getItemsByID(int[] itemDefIds)` result
  lifecycle (`SteamInventoryResult`, async `onResultReady`/`GetResultItems`
  per the fork's confirmed shape, `services/steamworks-inventory-bindings/plan.md`
  Acceptance Criterion 6) behind the same one-gateway-class discipline
  `SteamworksSteamFriendsGateway` already established — this is the sole
  `com.codedisaster.steamworks.SteamInventory*`-importing class this feature
  introduces (mirrors spec Public API item 3's "sole
  `SteamApps`-importing class" framing, updated for the real API).
- A small internal DLC-fallback path (same class or a private collaborator,
  implementation's discretion) covering `steamDlcAppId`-only catalog entries,
  reusing `SteamApps.isSubscribedApp`/`isDlcInstalled` exactly as the spec's
  original FR5.2 text describes — kept as documented secondary scope, not
  removed, since some catalog items may reasonably ship as whole-DLC bundles
  rather than per-instance inventory items.

**Reasoning this deviates from the spec's literal FR5/Compatibility text**:
those sections were accurate when written (steamworks4j 1.10.0 genuinely had
no `ISteamInventory` wrapper, confirmed by that spec's own source-tree
enumeration) but are now stale given the fork's existence — the honest
"ceiling" framing (Compatibility: "not reachable without a materially
different native dependency, an ADR-level decision") has already been
crossed by a separate, already-merged initiative. Continuing to plan against
the stale DLC-only ceiling would mean deliberately building throwaway scope
against a constraint that no longer exists. This plan does not delete the
DLC path outright (still useful, still spec-described, zero cost to keep as
a fallback branch) but makes the real Inventory API the primary, intended
path for any newly-configured catalog item.

**"Buy Now"/"Buy" (FR5.3), updated**: primary action calls the fork's
`SteamInventory.startPurchase(int[] itemDefIds, int[] quantities)` (per that
plan's own Public API sketch, `services/steamworks-inventory-bindings/plan.md`
Files to Create) for `inventoryItemDefId`-configured items — the async
purchase-authorization flow (`onStartPurchaseResult`) surfaces through the
same `SteamInventoryCallback` the fork's Java layer exposes. For
`steamDlcAppId`-only items (fallback path), FR5.3's original DLC-App-ID
store-page approach remains: `SteamFriends.activateGameOverlayToStore(int
appID, OverlayToStoreFlag flag)` (confirmed present, Existing
Implementation) opens the Steam Overlay directly — **no
`steam://store/<appid>` URI fallback is needed anywhere in this feature**,
resolving the spec's own flagged-open question outright.

**Honest acceptance-criteria consequence (carried into Acceptance Criteria
below)**: because no real App ID has configured Inventory items yet
(Existing Implementation), end-to-end `startPurchase`/real ownership
verification is **not** achievable in this pass, exactly mirroring the fork
initiative's own Test Strategy ceiling — this plan's Store panel acceptance
bar is "builds against the real API, calls the real methods, degrades to the
documented not-purchasable-yet placeholder when no def ID is configured,"
not "a real purchase was observed to complete."

### 2. FR7.6 sidebar rendering reuse — extract a shared rendering helper (spec's own preferred option, confirmed feasible)
Per platform-module code review (`FabricFriendsSidebarInjector.java`,
spot-read for shape only, not full extraction detail — implementation-time
task), the existing sidebar overlay rendering is not deeply coupled to a
specific vanilla screen type (it already takes arbitrary sidebar bounds per
the state machine's `isExpanded(mouseX, mouseY, ...)` signature, spec FR7.2)
— this plan adopts option (a) as final: extract the existing per-frame
sidebar-rendering code (currently inline in each platform's
`FabricFriendsSidebarInjector`) into a small, reusable
`FriendsSidebarRenderer`-shaped helper class per platform module (same
`platform/fabric-<version>` package, not a new Feature-layer class — it
still only consumes `FriendsSidebarFacade`/`FriendSidebarStateMachine`, no
new Feature-layer dependency), called by **both**
`FabricFriendsSidebarInjector` (existing, unchanged behavior on every other
vanilla screen) and `MainMenuScreen` (new, this feature's own sidebar
region/bounds). This is the "graduate a widget once a second consumer needs
the identical shape" case `ui-guidelines.md:204-216` describes, confirmed
applicable here rather than assumed. If implementation finds the extraction
non-trivial (tighter coupling to `FabricFriendsSidebarInjector`'s own screen
context than this plan's spot-read found), Risk 3 documents the fallback
(a second, main-menu-only rendering implementation) as an accepted
deviation, not a silent one.

### 3. 3D background implementation approach — vanilla `TitleScreen`/`PanoramaRenderer` as the literal starting point, `ModelPart` character per spec Architecture
No new decision needed beyond what spec Architecture already resolved (hand-
authored `ModelPart` hierarchy, no new asset-loading dependency) — this plan
adds only the **sequencing** consequence: because this is the single highest-
uncertainty piece in the whole feature (no existing precedent, Existing
Implementation above), it is scheduled as an early spike (Sequencing step 4,
before any tab-panel work), not folded into "just another FR8 task" at the
end. A minimal spike deliverable (a fixed-camera scene with sky gradient +
ground plane + one static `ModelPart` cube, no animation yet, on one platform
module) is this plan's own concrete exit criterion for "the approach works,"
before the full FR8.2–FR8.7 detail and the other two platform modules are
built out.

### 4. Config file placement and shape — no deviation from spec Configuration
`config/main-menu-store-catalog.json`/`config/main-menu-wardrobe.json`,
hand-rolled JSON, fail-closed-to-defaults, following
`SteamWorldHostingConfigIO`'s exact precedent (spec Configuration, Existing
Implementation already cites the exact file) — the only change from the
spec's literal JSON shape is the catalog schema's additional
`inventoryItemDefId` field (Decision 1):
```json
{
  "items": [
    {
      "id": "moss-cloak", "displayName": "Moss Cloak",
      "description": "A traveler's cloak, dyed in forest tones.",
      "category": "TORSO", "priceCents": 499, "originalPriceCents": 799,
      "inventoryItemDefId": null, "steamDlcAppId": null, "featured": true
    }
  ]
}
```

## Files to Create

### FR0 — no files created, only deletions (see Files to Delete)

### `api` module (top-level, zero dependencies)
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — enum
  `{WORLDS, SERVERS, STORE, WARDROBE}`.
- `api/src/main/java/de/lazuli/api/mainmenu/StoreItem.java` — record per
  Decision 1's updated schema.
- `api/src/main/java/de/lazuli/api/mainmenu/WardrobeSlot.java` — enum
  `{HEAD, TORSO, LEGS, FEET}`.
- `api/src/main/java/de/lazuli/api/mainmenu/CharacterPose.java` — record
  (bob offset `double`, arm-swing angle `double`, leg-sway angle `double`).
- (Placed in the same package hello-world-main-menu's `MainMenuHook.java`
  occupied before FR0 deleted it — Existing Implementation.)

### `features/main-menu` module (new Gradle subproject)
- `features/main-menu/build.gradle` — `dependencies { api project(':api');
  implementation project(':services') }` (identical shape to
  `features/server-browser/build.gradle`).
- `features/main-menu/README.md`.
- `config/`, `events/`, `gui/`, `mixins/` sub-packages — each a
  `package-info.java` placeholder, same rationale as every prior feature
  (`config/` is **not** permanently empty here, unlike `server-browser` —
  this feature has two real config files, `WardrobeConfigIO`/
  `StoreCatalogConfigIO`, so `config/` holds real classes, see below).
- `resources/` — `.gitkeep` (no bundled asset needed; placeholder swatches
  are hand-drawn `fill(...)` calls per platform code, same precedent
  `ServerBrowserRow`'s own placeholder-icon note already established).

**`services/` sub-package**:
- `MainMenuStateMachine.java` — pure logic per spec Public API item 2 (active
  tab toggle, world/server row expand toggle, servers sub-view toggle,
  wardrobe slot/equip transitions, modal-visibility flags).
- `IdleCharacterAnimator.java` — pure `CharacterPose poseAt(double
  elapsedSeconds)` (FR8.6's three-loop composition).
- `StoreCatalog.java` — loads/holds `List<StoreItem>`, accepts an injected
  `OwnershipChecker` (spec Public API item 2), exposes featured-item
  selection + category grouping, both plain-JVM-testable against a fake
  `OwnershipChecker`.
- `OwnershipChecker.java` — functional interface, `boolean isOwned(StoreItem
  item)` (Decision 1; implemented platform-side by
  `MainMenuStoreOwnershipChecker`).

**`config/` sub-package**:
- `WardrobeConfig.java`/`WardrobeConfigIO.java` — equip-map persistence
  (FR6.3), `SteamWorldHostingConfigIO` convention (Existing Implementation).
- `StoreCatalogConfigIO.java` — Store/Wardrobe catalog persistence (FR5.1),
  same convention, default built-in catalog on first run/malformed content
  (Configuration).

**`tests/`** (`src/test/java/de/lazuli/features/mainmenu/...`):
- `MainMenuStateMachineTest` — tab toggle (select/deselect/switch, FR2.2),
  world/server row single-expand-accordion toggle (FR3.3/FR4.2), servers
  sub-view toggle (FR4.1), wardrobe slot switch + equip-map mutation
  (FR6.1/FR6.2), modal open/close flags.
- `IdleCharacterAnimatorTest` — `poseAt` at known time offsets: bob
  amplitude/period (2.6s loop, FR8.6), arm-swing and leg-sway both nonzero
  and independently varying, continuity at loop boundaries (`poseAt(0)` ≈
  `poseAt(period)`).
- `StoreCatalogTest` — featured-item selection, category grouping, ownership
  branch against a fake `OwnershipChecker` (owned/not-owned/no-identifier-
  configured-yet cases, Decision 1).
- `WardrobeConfigIOTest`/`StoreCatalogConfigIOTest` — missing-file default
  creation, malformed-content fail-closed-to-default-with-warning, following
  `SteamWorldHostingConfigIOTest`'s own existing precedent shape (implementation
  confirms exact test-class name at build time).

### Platform modules — one composition root + Version Adapters per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
All under `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/`
(the now-vacated package, Existing Implementation) unless noted:
- `platform/fabric-<version>/src/main/java/de/lazuli/MainMenuClientInitializer.java`
  — new `ClientModInitializer`, composition root (spec Public API item 3):
  obtains `SteamworksServiceHandoff.require()`,
  `FriendsSidebarClientInitializer`'s already-published `FriendsSidebarFacade`,
  `ServerBrowserClientInitializer`'s already-published
  `ServerBrowserSessionFactory` (both via the same static-holder hand-off
  pattern `SteamworksServiceHandoff` already establishes, per spec
  Architecture — this plan does not invent a new hand-off mechanism, it
  reuses the existing one, confirming/creating a
  `FriendsSidebarHandoff`/`ServerBrowserSessionFactoryHandoff`-shaped static
  holder per platform module if one does not already exist for these two
  facades **specifically for cross-feature hand-off purposes** — implementation
  must first check whether `FriendsSidebarClientInitializer`/
  `ServerBrowserClientInitializer` already publish via an existing holder
  before adding a new one, per Risk 4), constructs
  `MainMenuStateMachine`/`IdleCharacterAnimator`/`StoreCatalog`/
  `WardrobeConfig`/`MainMenuStoreOwnershipChecker`, registers
  `ClientLifecycleEvents.CLIENT_STARTED` to `setScreen(new MainMenuScreen(...))`
  (FR1.1), and locates every vanilla "return to title screen" call site for
  this version (FR1.2 — disconnect screen's button action, world-exit path)
  to construct `MainMenuScreen` instead of vanilla `TitleScreen` there too
  (concrete per-version call sites are a Sequencing-step-5 lookup task, not
  resolved by this plan since they were not read this pass).
- `.../mainmenu/MainMenuScreen.java` — the new `Screen` subclass (spec Public
  API item 3): composes the background renderer, tab bar, four tab-panel
  renderers, and the shared sidebar-rendering helper (Decision 2). Resets all
  `MainMenuStateMachine` state on construction (FR1.3).
- `.../mainmenu/MainMenuBackgroundRenderer.java` — owns the 3D scene (FR8):
  camera/projection setup, sky/sun/mountains/ground static geometry (built
  once, Performance), `ModelPart` character posed per-frame from
  `IdleCharacterAnimator.poseAt(...)` (Decision 3's spike is this class's
  first deliverable).
- `.../mainmenu/MainMenuStoreOwnershipChecker.java` — implements
  `OwnershipChecker` against the fork's real `SteamInventory` API, with the
  DLC-fallback branch (Decision 1); the sole
  `com.codedisaster.steamworks.SteamInventory*`-importing class this feature
  introduces.
- `.../mainmenu/WorldsPanel.java`, `ServersPanel.java`, `StorePanel.java`,
  `WardrobePanel.java` — the four tab-panel renderers (FR3–FR6), each reading
  `MainMenuStateMachine`'s relevant slice of state and (Servers/Store panels
  only) the reused `ServerBrowserSession`/`StoreCatalog`+`OwnershipChecker`
  data.
- `.../mainmenu/FriendsSidebarRenderer.java` — the extracted shared helper
  (Decision 2), called by both this feature's `MainMenuScreen` and the
  existing `FabricFriendsSidebarInjector` (modified, see Files to Modify).
- `.../mainmenu/DirectConnectModalScreen.java`,
  `AddServerModalScreen.java` — small feature-owned modal screens (FR4.3),
  reusing vanilla's own equivalent modal if one is confirmed present per
  version at implementation time (spec's own explicit "confirm before
  building a new one" caveat), else the from-scratch
  `ServerBrowserPasswordPromptScreen`-shaped precedent (spec Public API
  item 7 reference, `server-browser`).

## Files to Modify
- `settings.gradle` — add `include 'features:main-menu'`; remove
  `include 'features:hello-world-main-menu'` (`settings.gradle:23`, FR0.2).
- `platform/fabric-26.2/build.gradle`, `platform/fabric-26.1/build.gradle`,
  `platform/fabric-1.21.11/build.gradle` — each: remove
  `implementation project(':features:hello-world-main-menu')` (line 15,
  confirmed all three, FR0.3); add
  `implementation project(':features:main-menu')`.
- `platform/fabric-26.2/src/main/resources/fabric.mod.json`,
  `platform/fabric-26.1/.../fabric.mod.json`,
  `platform/fabric-1.21.11/.../fabric.mod.json` — each: remove
  `"de.lazuli.HelloWorldMainMenuClientInitializer"` from the `"client"` array
  (confirmed present as the first entry, 26.2 line 22, FR0.4); add
  `"de.lazuli.MainMenuClientInitializer"` as the **last** entry (after
  `ServerBrowserClientInitializer`, load-bearing ordering, Existing
  Implementation).
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`,
  same path in `fabric-26.1`/`fabric-1.21.11` (all three) — **added during
  implementation, not in the original spec/plan text** (Existing
  Implementation's correction above): delete the private nested
  `HelloWorldMainMenuCloudSyncAdapter` class, remove the
  `HelloWorldMainMenuConfigIO` import, remove the `helloWorldConfigPath` local
  and its `cloudSyncables` list entry (replace with `List.of()` unless another
  syncable already exists to keep in that list).
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`
  (all three modules, exact per-module path confirmed via the spec's own
  citation `platform/fabric-26.2/.../FabricFriendsSidebarInjector.java`) —
  modified to delegate its own per-frame sidebar rendering to the new,
  extracted `FriendsSidebarRenderer` helper (Decision 2), preserving its
  existing behavior on every other vanilla screen unchanged.
- `.claude/context/minecraft.md` — gains new rows once implementation
  confirms (via real compile) the exact vanilla disconnect/world-exit
  call sites per version (FR1.2) and the `ServerBrowserListWidget`-shaped
  base class this feature's own `ServersPanel`/Browser sub-view row list
  needs (mirrors `server-browser` Decision 3's precedent, re-derived here
  for this panel's own layout) — not modified by this planning pass itself,
  per this repo's living-record convention.

## Files to Delete (FR0)
- `features/hello-world-main-menu/` (entire directory: `src/`, `build.gradle`,
  `README.md`, `implementation-plan.md`, `specification.md`).
- `platform/fabric-26.2/src/main/java/de/lazuli/HelloWorldMainMenuClientInitializer.java`,
  same path in `fabric-26.1`/`fabric-1.21.11`.
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/FabricMainMenuHook.java`,
  same path in `fabric-26.1`/`fabric-1.21.11` (Existing Implementation's
  second catch — not covered by the spec's own literal FR0 wording).
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuHook.java` (Existing
  Implementation's third catch — not covered by the spec's own literal FR0
  wording).

## Interfaces
- `api/.../mainmenu/{MainMenuTab, StoreItem, WardrobeSlot, CharacterPose}` —
  plain data types crossing the Platform/Feature boundary.
- `features/main-menu/services/OwnershipChecker` — implemented by
  `MainMenuStoreOwnershipChecker` (Platform, Decision 1), consumed by
  `StoreCatalog` (Feature).
- `api/.../serverbrowser/{ServerBrowserSession, ServerBrowserSessionFactory}`
  — reused directly, unmodified (Existing Implementation).
- `features/friends-sidebar/services/FriendsSidebarFacade` — reused
  directly, unmodified (Existing Implementation, no new hook needed per
  spec Architecture's "no gap found").

## Services
- No new `services/`-module (shared-across-features) capability introduced.
  `services/steamworks/SteamworksSteamFriendsGateway`-style wrapping of
  `SteamInventory` (Decision 1) stays feature-local
  (`MainMenuStoreOwnershipChecker`, platform layer) — no second consumer of
  `SteamInventory` exists in this repo yet, so graduate-on-second-use is not
  triggered (matches `server-browser`'s own precedent reasoning for
  `SteamMatchmakingServers`).

## Feature Classes
Enumerated fully under Files to Create above (`api/` top-level types,
`features/main-menu/services/*`, `features/main-menu/config/*`). All plain
Java; zero `net.minecraft.*`/steamworks4j import outside the platform-layer
`MainMenuStoreOwnershipChecker` (mirrors `server-browser` NFR3's discipline,
not itself a spec NFR here but adopted as this plan's own consistency bar).

## Tests

### Test Strategy
- **Unit-testable (plain JVM, no Minecraft/steamworks4j import)**:
  `MainMenuStateMachineTest`, `IdleCharacterAnimatorTest`, `StoreCatalogTest`
  (against a fake `OwnershipChecker`), `WardrobeConfigIOTest`,
  `StoreCatalogConfigIOTest` — all under `features/main-menu/src/test`,
  `gradlew :features:main-menu:test` runs with no Minecraft jar on its test
  classpath (mirrors `server-browser` NFR1's own bar).
- **Not unit-testable, manual per-platform-module in-game verification
  required** (rendering, `Screen`/widget classes, the 3D background,
  `MainMenuStoreOwnershipChecker`'s real `SteamInventory` calls):
  - Every tab's open/close/active-state visuals (FR2), Worlds/Servers row
    expand-collapse (FR3.3/FR4.2), the real "Create New World"/vanilla
    world-load/Edit transitions (FR3.5–FR3.7).
  - The Servers Browser sub-view's full filter/sort/connect/password-prompt
    flow, reusing `server-browser`'s own manual-verification checklist shape
    (spec UI section) — session-per-open lifecycle (`newSession()`/`close()`)
    spot-checked via log output, same discipline `server-browser`'s own plan
    already established for `releaseRequest`.
  - Store panel: **with Steam available and a catalog item configured with a
    real `inventoryItemDefId`** — since no real App ID has Inventory items
    configured yet (Existing Implementation), this branch cannot show a
    genuinely-owned item end-to-end; verification instead confirms
    `getAllItems`/`getItemsByID` call without crashing and the "not
    purchasable yet" placeholder renders correctly for every catalog item in
    the dev-time default catalog (all of which ship with `null`
    `inventoryItemDefId`/`steamDlcAppId`, Configuration) — mirrors the fork
    initiative's own honest ceiling (Existing Implementation). A hand-swapped
    fake `OwnershipChecker` (already unit-tested via `StoreCatalogTest`) is
    the only way to visually verify the "owned" rendered state pre-real-App-ID,
    same concrete mitigation the spec's own Compatibility section
    anticipated for the (now-superseded) DLC-only model.
  - Store panel with Steam unavailable — status-message fallback (mirrors
    `server-browser` FR5.1/FR5.2 shape, spec FR4.6 reuse).
  - Wardrobe equip/persist-across-restart (FR6.3) — equip an item, restart
    the client, confirm the equip map survives.
  - Sidebar hover-expand/collapse and Steam-unavailable status state inside
    `MainMenuScreen` specifically (FR7), confirming the extracted
    `FriendsSidebarRenderer` (Decision 2) renders identically to its
    pre-extraction behavior on every other vanilla screen (regression check
    on `FabricFriendsSidebarInjector`'s existing behavior, Files to Modify).
  - The 3D background's frame-time cost on each target version (Performance)
    — a simple visible/overlay frame-time counter or profiler capture during
    manual verification, not a hard automated gate (spec Performance's own
    "implementation-time measurement, not asserted here" framing).
  - Full-repeat of the above across all three platform modules (Compatibility).

## Dependencies
- **No new external Maven/Gradle dependency added by this plan.** The
  Steamworks fork coordinate
  (`com.github.Probastian.steamworks4j:steamworks4j:v1.10.0-inventory.1`) is
  already resolved into this mod's build by the already-complete
  `services/steamworks-inventory-bindings` initiative (Existing
  Implementation) — this plan consumes it, does not introduce it. Per the
  planning-agent's own dependency-verification rule: since this is not a new
  coordinate this plan is proposing, no fresh Maven-Central/registry lookup
  is performed here; the fork's own JitPack build-log success and this mod's
  own green `gradlew build` (both already recorded in
  `services/steamworks-inventory-bindings/plan.md`'s Final Status) are the
  existing verification this plan relies on instead.
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:main-menu` → `api` (`api` configuration)
  - `features:main-menu` → `services` (`implementation` configuration)
  - `platform:fabric-26.2` → `features:main-menu` (`implementation`)
  - `platform:fabric-26.1` → `features:main-menu` (`implementation`)
  - `platform:fabric-1.21.11` → `features:main-menu` (`implementation`)
- `features:main-menu` does **not** depend on `features:friends-sidebar` or
  `features:server-browser` directly (Dependency Rules, Forbidden: Feature →
  Feature) — both are composed only at the platform composition root (spec
  Architecture, Existing Implementation).
- **Removed dependency edges (FR0)**: `platform:fabric-26.2/26.1/1.21.11` →
  `features:hello-world-main-menu` (all three), and
  `settings.gradle`'s inclusion of that module.

## Sequencing (order of implementation)
1. **FR0 removal** (Files to Delete/Modify above) — a clean `gradlew build`
   across all three platform modules with the old module fully gone,
   confirmed *before* any new `features/main-menu` code is added, since this
   plan's own new platform-layer classes reoccupy the exact
   `de.lazuli.mainmenu` package/`de.lazuli.api.mainmenu` package the deleted
   files vacated (Existing Implementation's second/third catches) — doing
   this first avoids ever having both old and new classes coexisting in the
   same package.
2. `features/main-menu` module scaffold (`build.gradle`, package
   placeholders, `settings.gradle` entry) + `api/.../mainmenu/*` types
   (Decision 1's updated `StoreItem` schema).
3. Pure-logic classes, testable immediately: `MainMenuStateMachine`,
   `IdleCharacterAnimator`, `StoreCatalog` (against a fake
   `OwnershipChecker`), `WardrobeConfigIO`/`StoreCatalogConfigIO` — plus
   their unit tests (Test Strategy).
4. **3D background spike** (Decision 3) on one platform module
   (`fabric-26.2`, matching this repo's own "verify the newest/most-diverged
   module's assumptions first" convention, `server-browser` Sequencing) —
   read vanilla `TitleScreen`/`PanoramaRenderer`'s camera/projection setup
   first; build the minimal fixed-camera/sky/ground/one-cube spike; confirm
   it coexists correctly with `GuiGraphics`'s own 2D draw calls in one
   `Screen.render`/`extractRenderState` cycle (spec Compatibility's own
   flagged open question) before proceeding.
5. Full `MainMenuBackgroundRenderer` (FR8.2–FR8.7, animated `ModelPart`
   character posed from `IdleCharacterAnimator`) on `fabric-26.2`.
6. `MainMenuScreen` skeleton + tab bar (FR1, FR2) on `fabric-26.2`, composing
   the background renderer; confirm FR1.4's "no tab active" state renders
   background + title/subtitle + tab bar + collapsed sidebar only.
7. Worlds panel (FR3) — real vanilla world-list/Create-World/Play/Edit
   integration, no mock toast (FR3.5).
8. Sidebar integration (FR7, Decision 2) — extract `FriendsSidebarRenderer`
   from `FabricFriendsSidebarInjector`, wire into `MainMenuScreen`; regression-
   check the existing injector's behavior on other vanilla screens is
   unchanged.
9. Servers panel (FR4) — Saved sub-view first (same row pattern as Worlds),
   then Browser sub-view wired to a fresh `ServerBrowserSession` per
   sub-view-activation (Existing Implementation), including the Direct
   Connect/Add Server modals and the reused password-prompt flow.
10. Store panel (FR5, Decision 1) — `MainMenuStoreOwnershipChecker` against
    the real `SteamInventory` API (primary) + DLC fallback branch;
    `activateGameOverlayToStore`/`startPurchase` wiring; default catalog with
    every dev-time item's identifiers `null` (Configuration).
11. Wardrobe panel (FR6) — slot selector, item grid filtered by
    owned-or-equipped, equip-map persistence.
12. FR1.2's disconnect/world-exit "return to `MainMenuScreen`" call sites —
    per-version lookup and wiring, on `fabric-26.2` first.
13. Port the confirmed `fabric-26.2` design to `fabric-26.1` (near-identical,
    same Mojang mapping family), then `fabric-1.21.11` (Yarn mapping family,
    same rename discipline `server-browser`'s own Sequencing step 7 already
    established).
14. Full manual verification matrix (Test Strategy) across all three
    platform modules, Steam both running and not running, on the one machine
    currently confirmed to have Windows x64 Steamworks-Inventory natives
    (Existing Implementation's carried-forward Windows-only limitation).

## Risks
1. **3D-in-a-`Screen` rendering is the single highest-uncertainty piece in
   this entire plan** (spec Architecture: no existing precedent beyond
   vanilla's own panorama background) — mitigated by scheduling it as an
   explicit early spike (Sequencing step 4) with its own narrow exit
   criterion (a working minimal scene coexisting with 2D `GuiGraphics` calls
   in one render cycle) before any of FR2–FR7's panel work depends on it
   being fully correct. If the spike reveals a real per-frame matrix-state
   conflict with `GuiGraphics`'s own draw calls, this is the single most
   likely source of a scope/approach revision mid-implementation — flagged
   here so it surfaces immediately rather than at the end of the plan.
2. **Steamworks Inventory fork is Windows-x64-only right now** (Existing
   Implementation, carried forward unchanged from
   `services/steamworks-inventory-bindings/plan.md`'s own Risk 2/Final
   Status) — this plan cannot fix this; Store panel functionality involving
   real `SteamInventory` calls (`MainMenuStoreOwnershipChecker`) will not
   build/run correctly on Linux/macOS players' machines until a follow-up
   fork release (`v1.10.0-inventory.2`+) adds those native targets. Mitigation:
   `MainMenuStoreOwnershipChecker` still degrades to the documented
   "not-purchasable-yet" placeholder if the native call itself throws/fails
   to initialize (same defensive-catch discipline `ServerBrowserQuery`
   already established for its own steamworks4j calls, per `server-browser`
   Decision 1) — this feature does not crash the whole Store panel on a
   platform where the fork's natives are absent, but this is a documented
   degradation, not a fix.
3. **FR7.6's shared-rendering-helper extraction (Decision 2) is only
   spot-read, not fully verified, this planning pass** — if
   `FabricFriendsSidebarInjector`'s current sidebar-rendering code turns out
   more tightly coupled to its own screen-injection context than this plan's
   spot-read found, the documented fallback (a second, `MainMenuScreen`-only
   rendering implementation, per spec FR7.6's own explicit allowance) is
   available; implementation should attempt the extraction first
   (Sequencing step 8) and only fall back if it proves genuinely
   impractical, recording the reason if so.
4. **Cross-feature facade/factory hand-off mechanism for
   `FriendsSidebarFacade`/`ServerBrowserSessionFactory` is not confirmed to
   already exist in a reusable static-holder form** — `SteamworksServiceHandoff`
   is confirmed to exist (Existing Implementation), but whether
   `FriendsSidebarClientInitializer`/`ServerBrowserClientInitializer` already
   publish their own facade/factory via an equivalent holder (versus
   constructing and using them purely locally, with no cross-feature
   hand-off need until now) is not verified this pass — an implementation-time
   lookup task (Sequencing step 8/9's first sub-step) that may require adding
   a small new `FriendsSidebarFacadeHandoff`/`ServerBrowserSessionFactoryHandoff`
   static holder per platform module, mirroring `SteamworksServiceHandoff`'s
   own shape, if neither currently exists in a form this feature's
   composition root can consume.
5. **FR1.2's exact per-version vanilla "return to title screen" call sites
   (disconnect screen button, world-exit path) are not enumerated by this
   plan** (spec itself flags this as implementation-time work) — sequenced
   last among `fabric-26.2`'s own build-out (Sequencing step 12) specifically
   so it does not block the rest of the panel work, but a missed call site
   would silently regress back to vanilla `TitleScreen` after a disconnect —
   flagged as a concrete manual-verification checklist item (Test Strategy),
   not assumed correct by code review alone.
6. **No real Steam App ID with configured Inventory items exists** (Existing
   Implementation, carried forward from the fork initiative's own Risk 4) —
   this plan's Store panel acceptance bar is capped accordingly (Decision 1,
   Acceptance Criteria) rather than promising a verification depth that
   isn't actually achievable yet.

## Acceptance Criteria
Mapped to the specification's own Goals/Requirements, updated for Decision
1's real-`SteamInventory` Store panel change:

- **FR0.1–FR0.5** — `features/hello-world-main-menu/` and all three deletions
  identified in Existing Implementation (platform `FabricMainMenuHook.java`
  ×3, `api/.../mainmenu/MainMenuHook.java`) are gone; `settings.gradle`/all
  three platform `build.gradle`s/`fabric.mod.json`s carry no reference;
  `gradlew build` succeeds across all three platform modules with the old
  module fully removed, confirmed as its own standalone step (Sequencing
  step 1) before any new code is added.
- **FR1.1–FR1.4** — `MainMenuScreen` is the initial screen on
  `ClientLifecycleEvents.CLIENT_STARTED`; in-game confirms it is also
  reached after disconnect/world-exit (FR1.2, no vanilla `TitleScreen`
  reachable via any of this repo's own vanilla call sites); a fresh
  `MainMenuScreen` instance always starts with no tab active/no expanded
  row/Saved sub-view/collapsed sidebar (FR1.3); background continues
  rendering with no tab active (FR1.4).
- **FR2.1–FR2.3** — Four tab buttons render correctly scaled at multiple GUI
  scales/window sizes; active/inactive/hover states match Design Tokens'
  OKLCH-derived ARGB constants (full `0xFF` alpha, per spec UI's own
  caution); click-to-select/deselect/switch behavior matches FR2.2 exactly.
- **FR3.1–FR3.7** — Worlds panel lists real vanilla saved-world data;
  single-expand accordion behavior (FR3.3); Create New World opens the real
  vanilla `CreateWorldScreen`, no toast placeholder (FR3.5); Play/Edit
  delegate to vanilla's own real world-load/edit flows (FR3.6/FR3.7).
- **FR4.1–FR4.6** — Saved sub-view lists real vanilla saved servers; Browser
  sub-view renders `ServerBrowserSession`'s live data with working
  filter/sort/refresh/connect/password-prompt (reusing
  `ServerBrowserPasswordPromptScreen`'s existing v1 stub unchanged, FR4.5);
  session opened fresh per Browser-sub-view-activation, closed on
  sub-view-leave/screen-close (spot-checked via log, mirrors
  `server-browser`'s own release-discipline check); Steam-unavailable status
  message renders in place of the table (FR4.6).
- **FR5.1–FR5.5, as updated by Decision 1** — catalog config includes the new
  `inventoryItemDefId` field; `MainMenuStoreOwnershipChecker` calls the
  fork's real `SteamInventory.getAllItems()`/`getItemsByID(...)` for
  inventory-item-def-configured entries and `SteamApps.isDlcInstalled`/
  `isSubscribedApp` for DLC-App-ID-only entries, degrading to the
  "not-purchasable-yet" placeholder when neither identifier is configured
  (the honest, expected dev-time default per Configuration); "Buy"
  primary-action wiring calls `startPurchase`/`activateGameOverlayToStore` as
  appropriate (Decision 1) with **no** `steam://store/<appid>` URI fallback
  needed anywhere; ownership re-checked on next Store-panel open, no live
  push assumed (FR5.5). **Explicitly not required**: a real end-to-end
  purchase/ownership observation against a genuine configured Inventory item
  (Existing Implementation, Risk 6) — this criterion is met by the code path
  being real and exercised against test App ID `480`/a fake `OwnershipChecker`
  for visual "owned" verification, not by observing a real purchase.
- **FR6.1–FR6.4** — Slot selector switches the item grid; equip persists in
  `config/main-menu-wardrobe.json` across a client restart (FR6.3, in-game
  verified); no skin-layer rendering of equipped cosmetics (FR6.4, explicit
  non-goal, not attempted).
- **FR7.1–FR7.6** — Collapsed/expanded sidebar states render `FriendsSidebarFacade`
  data identically to its existing pre-extraction rendering elsewhere in the
  repo (regression check on `FabricFriendsSidebarInjector`); hover-expand
  driven by `FriendSidebarStateMachine.isExpanded` with this screen's own
  bounds (FR7.2); Steam-unavailable fallback renders (FR7.5); shared
  `FriendsSidebarRenderer` extraction confirmed working (Decision 2) or the
  documented fallback recorded if not (Risk 3).
- **FR8.1–FR8.8** — Perspective 3D background renders continuously behind
  the UI regardless of tab state; sky/sun/mountains/ground/character all
  match the design doc's described placement/behavior; three simultaneous,
  independently-looping animations on the character (bob/arm-swing/leg-sway,
  `IdleCharacterAnimatorTest` covers the math, in-game confirms the visual);
  camera/lighting fixed, no dynamic time-of-day (FR8.7); zero I/O/network/
  Steamworks dependency on the render-thread background pass (FR8.8, code
  review).
- **Compatibility/Performance** — `gradlew build` succeeds across all three
  platform modules with `features:main-menu` in place and the old module
  fully removed; the full manual verification matrix (Test Strategy) passes
  on all three targets, Steam both running and not running (Store panel's
  real-`SteamInventory` branch specifically only verifiable on the Windows x64
  machine per Risk 2 — Linux/macOS verification of that branch is an
  explicitly out-of-scope, documented gap for this pass, not silently
  skipped); background frame-time cost profiled and recorded per target
  version (a single-digit-percentage-of-16.6ms informal target, per spec
  Performance's own non-hard-gate framing).

## Open Questions
- Exact per-version vanilla disconnect/world-exit "return to title screen"
  call sites (FR1.2) — not enumerated by this plan (Risk 5); resolved during
  implementation (Sequencing step 12), logged in `.claude/context/minecraft.md`
  per this repo's own convention if any cross-version divergence is found.
- Whether `FriendsSidebarClientInitializer`/`ServerBrowserClientInitializer`
  already publish their facade/factory via a reusable static-holder
  hand-off, or whether this plan's `MainMenuClientInitializer` must add a new
  one per platform module (Risk 4) — resolved during implementation
  (Sequencing steps 8/9's first sub-step).
- Whether FR7.6's shared-rendering-helper extraction is actually clean given
  `FabricFriendsSidebarInjector`'s real current shape (Risk 3, only
  spot-read this pass) — resolved during implementation (Sequencing step 8);
  the documented fallback (a second, main-menu-only rendering
  implementation) is pre-approved by the spec itself if extraction proves
  impractical.
- Whether vanilla's own Direct-Connect/Add-Server modal screens exist and are
  reusable per version, versus this feature needing to build its own
  (FR4.3's own "confirm before assuming" caveat) — resolved during
  implementation, per-version, before `DirectConnectModalScreen`/
  `AddServerModalScreen` are built.
- When Linux/macOS Steamworks-Inventory natives become available
  (`services/steamworks-inventory-bindings` follow-up release,
  `v1.10.0-inventory.2`+) — out of this plan's control (Risk 2); this
  feature's own Store panel code does not need to change when that happens,
  only the pinned `steamworks4j_version`/fork tag this mod's `gradle.properties`
  already points at, per that initiative's own documented re-repoint process.
