# Main Menu Post-Launch Fixes — Implementation Plan

Companion to `features/main-menu/specification-post-launch-fixes.md`. All paths below relative to `platform/fabric-26.2/src/main/java/de/lazuli/` unless noted; `fabric-26.1` and `fabric-1.21.11` carry parallel copies of every affected class and must each be diffed individually, not assumed identical.

## Existing Implementation
- `mainmenu/MainMenuScreen.java`: constructs `FriendSidebarWidget` with `richPresenceFacade = null` (line 79-80); `panelX()` returns fixed `24` (line 100-102); `panelWidth()` = `width - TAB_BAR_WIDTH(108) - 48 - sidebarCollapsedWidth(84)` (line 108-114); `renderTitle` draws "STONEBOUND"/"OVERHAUL MOD · V2.1" (line 145-151); tab bar rendering and `mouseClicked` hit-testing are hand-rolled fills with no sound (line 153-224).
- `friends/FriendSidebarWidget.java`: constructor takes `RichPresenceFacade richPresenceFacade` as 6th arg (line 184-186); `extractWidgetRenderState` calls `richPresenceFacade.localPresenceStatus()` unconditionally when the own-profile row is present (~line 420). `FabricFriendsSidebarInjector` already supplies a real, non-null facade elsewhere — the reference pattern to copy.
- `mainmenu/WorldsPanel.java`: `createButton` added once in `init()` (line 67-72) with no visibility-toggle; `render()` draws no thumbnail (line 74-111); world data via `LevelStorageSource`/`LevelSummary` (line 49-64).
- `mainmenu/ServersPanel.java`: already has the `tabActive`/`applyVisibility()` pattern (line 132-154) — the template FX3 copies. `renderSaved()` (line 211-243) draws only ping-dot + name + `playersText` (empty when unpinged); no icon, no MOTD, no refresh anywhere in Saved sub-view; `savedServers` is only `.load()`-ed (line 69-73), never pinged. `refreshButton` (line 82-84, 148) is Browser-sub-view-only. `onRefreshClicked()` (line 175-181) only touches `browserSession`.
- `mainmenu/MainMenuBackgroundRenderer.java`: `render()` (line 228-243) draws the scene via `guiGraphics.skin(sceneModel, PALETTE, 30f, 0f, 0f, 0f, 0, 0, screenWidth, screenHeight)` — destination rect is already full-screen; character sized `Math.max(160, screenHeight/2)`, positioned at `screenWidth*0.08` from left, flush to bottom (line 236-242). Scene geometry built in `buildScene()` (line 129-185) with fixed model-space extents.
- `MainMenuTab`, `MainMenuStateMachine`, `MainMenuScreenFactoryHandoff` — implementation must open these when wiring FX1's new constructor parameter and FX3's `setTabActive` call site.

## Files to create
1. `mainmenu/IconTextureCache.java` — one per platform module. Small shared cache keyed by level ID / server-list index; loads and uploads a world's `icon.png` or a server's favicon bytes to a texture once, returns a cached `Identifier` on subsequent frames, with a fixed fallback texture for missing/unloaded icons. Used by both `WorldsPanel` (FX2) and `ServersPanel` (FX4.1). No new module/`api` package needed — platform-local, matching `AvatarTextureCache`'s existing precedent.

## Files to modify
Per platform module (×3):

1. **`mainmenu/MainMenuScreen.java`**
   - FX1: thread a real `RichPresenceFacade` into the `FriendSidebarWidget` constructor instead of `null`; add whatever constructor parameter or internal resolution is needed; update `MainMenuScreenFactoryHandoff` call site.
   - FX3: call `worldsPanel.setTabActive(...)` alongside the existing `serversPanel.setTabActive(...)` in `mouseClicked`; set correct initial visibility for both panels' tab-scoped buttons in `init()`.
   - FX5: delete the two `guiGraphics.text` calls in `renderTitle` (and the method itself if unused elsewhere).
   - FX6: `panelX()` → `width / 3`; `panelWidth()` recomputed; panel background fill no longer bleeds `-12` left of the new `panelX()`; clamp so the panel never goes negative-width at small window sizes (reserved region shrinks first per FX6.2).
   - FX9: add click-sound calls to the hand-rolled tab-bar hit-test branch in `mouseClicked`.
2. **`MainMenuScreenFactoryHandoff.java`**
   - FX1: supply the real `RichPresenceFacade` at the `MainMenuScreen` construction call site, obtained the same way `FabricFriendsSidebarInjector`'s other consumer already does.
3. **`mainmenu/WorldsPanel.java`**
   - FX2: draw world icon thumbnail per row (compact + expanded sizes) via `IconTextureCache`, with fallback.
   - FX3: add `setTabActive(boolean)` toggling `createButton.visible`.
   - FX9: add click-sound calls to the hand-rolled Play/Edit button hit-test branches.
4. **`mainmenu/ServersPanel.java`**
   - FX4.1: draw server icon per Saved row via `IconTextureCache`, with fallback.
   - FX4.2: new Saved-only refresh `Button`, visibility tied to `SAVED` sub-view, click handler re-pings `savedServers`.
   - FX4.3: render "—"/"Pinging..." instead of blank when `server.players == null`.
   - FX4.4: render MOTD per row, reusing vanilla's formatting-code parsing.
   - FX4.5: trigger a ping pass over `savedServers` the first time the Saved sub-view becomes visible (in `setTabActive`/`toggleSubView`, guarded to fire once per screen open).
   - FX9: add click-sound calls to the hand-rolled Saved-Connect-button and Browser column-header/row-join hit-test branches.
5. **`mainmenu/MainMenuBackgroundRenderer.java`**
   - FX7: recompute `charSize`/`charX0`/`charY0` from the reserved-left-third region's actual bounds. This class needs to either receive that region's bounds as a parameter to `render(...)` (e.g. add a `reservedRegionWidth` parameter) or recompute `width/3` itself — pick one so `MainMenuScreen` and this class share one region definition rather than two independent `width/3` computations that could drift.
   - FX8: tune `scale`/pivot parameters in the `skin(...)` call and/or model-space box extents in `buildScene()` to fill the destination rect without appearing small/centered/bottom-stuck. Iterative visual tuning, verified by manual screenshot comparison.

No changes anticipated to `MainMenuTab`, `MainMenuStateMachine`, `StorePanel`, `WardrobePanel`, `AddServerModalScreen`, `DirectConnectModalScreen`.

## Risks
- **R1 — Exception-swallowing behavior unconfirmed (FX1).** The root-cause theory (NPE from null `RichPresenceFacade` silently dropping the sidebar widget's render state) is well-grounded but the exact `Screen`/widget-list exception-handling mechanism wasn't traced fully. Implementation must reproduce the bug locally before applying the fix; if the real cause differs, treat as missing scope and flag it.
- **R2 — Three-platform divergence.** `fabric-1.21.11` may use an older direct-`GuiGraphics` render model (no `extractRenderState`/`GuiGraphicsExtractor`) vs. 26.x's pipeline — FX7/FX8's `MainMenuBackgroundRenderer` fix and FX9's sound-call fix may need materially different code shapes on 1.21.11, not a mechanical port. Handle each platform module as its own pass; verification checks all three independently.
- **R3 — `IconTextureCache` texture upload safety.** Reading/decoding icon bytes (disk for worlds, network for server favicons) must happen off the render thread, with only the final GL upload on the render thread — mirror whatever `AvatarTextureCache` already does rather than inventing new threading logic.
- **R4 — FX8 may not fully resolve via tuning.** Per spec FX8.3, if scale/pivot tuning doesn't visually fix the background, reframe as a Future Extension (placeholder-model limitation) rather than iterate unboundedly. Timebox this.
- **R5 — FX6's fixed `width/3` at small window sizes.** Could leave the panel too narrow or negative-width once `TAB_BAR_WIDTH(108)` + `sidebarCollapsedWidth(84)` + panel minimums are subtracted. Per FX6.2 (panel has priority), clamp the reserved region's effective width downward before the panel goes negative — needs a concrete minimum-panel-width floor, decided at implementation time.

## Dependencies
No new external dependencies. All fixes use existing Minecraft/Fabric APIs already in use elsewhere in this codebase (vanilla world/server icon loading, vanilla ping mechanism, vanilla `SoundEvents.UI_BUTTON_CLICK`/`SimpleSoundInstance`, existing `RichPresenceFacade`/`AvatarTextureCache`-pattern reuse).

## Test strategy
- **FX1:** Manual verification — open the main menu, confirm the sidebar's collapsed avatar rail renders immediately and expands on hover with no exception logged.
- **FX2/FX4.1:** Manual verification against at least one world with an `icon.png`, one without, one saved server with a favicon, one without — confirm fallback rendering in all four cases. `IconTextureCache` gets unit tests around cache-hit/cache-miss/fallback logic if testable without a live GL context, otherwise manual-only.
- **FX3:** Manual verification — switch to each of the four tabs, confirm "+ Create New World" is visible only on Worlds.
- **FX4.2/4.3/4.4/4.5:** Manual verification — open Servers → Saved with at least one reachable server and one unreachable/stale one, confirm ping-on-open populates player count/MOTD/icon, confirm the new refresh button re-triggers pinging, confirm pending state before the first ping resolves.
- **FX5:** Manual/visual verification — confirm neither placeholder string appears anywhere.
- **FX6/FX7/FX8:** Manual/visual verification across at least two aspect ratios (16:9 and an ultrawide or narrow window) — confirm the left third shows only background+character with no panel bleed, character is appropriately large/positioned, background fills the region without the previous small/centered/bottom-stuck look (or a documented R4 decision).
- **FX9:** Manual verification — click every listed hand-rolled control and confirm the standard UI click sound plays.
- No automated UI/rendering test harness exists in this repo for screen-level behavior, consistent with the original feature's own test strategy — this pass follows the same manual-verification convention plus any small pure-logic unit tests noted above.

## Acceptance criteria
1. Friends sidebar visible and functional (collapsed rail + hover-expand) immediately on opening the main menu, on all three platform modules.
2. Every saved world row shows a real or fallback icon thumbnail, compact and expanded.
3. "+ Create New World" appears only when the Worlds tab is active.
4. Servers → Saved sub-view shows: server icon/fallback, a working Saved-specific refresh control, player count (with pending-state placeholder before first ping), and MOTD — for every saved server, on tab open and after manual refresh.
5. No "STONEBOUND" or "OVERHAUL MOD · V2.1" (or equivalent placeholder) string renders anywhere in the main menu.
6. The left third of the screen (`width/3`) shows only the 3D background/character, with no panel fill/text/background overlay bleeding into it at typical window sizes; the panel grows/shrinks with window width per FX6.2, with the reserved background region yielding first at small sizes.
7. The 3D character reads as clearly larger and correctly framed within the reserved region (bottom-left of that region).
8. The 3D background scene visually fills its region without appearing small, centered, or stuck to the bottom — or a documented note attributing any remainder to the placeholder-model limitation (FX8.3/R4).
9. Every listed custom-hit-tested control plays the standard vanilla UI click sound on click, on all three platform modules.
10. All of the above hold independently on `fabric-1.21.11`, `fabric-26.1`, and `fabric-26.2`.
