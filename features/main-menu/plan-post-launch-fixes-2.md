# Main Menu Post-Launch Fixes 2 — Implementation Plan

Companion to `features/main-menu/specification-post-launch-fixes-2.md`. Paths relative to `platform/fabric-26.2/src/main/java/de/lazuli/` unless noted; `fabric-26.1`/`fabric-1.21.11` carry parallel files, each must be diffed individually (confirmed divergence risk from round 1 still applies, especially for FX14/FX15).

## Existing Implementation
- `mainmenu/ServersPanel.java`: `renderSaved` (line 268-335) and `renderBrowser` (line 337+) are independent methods, no shared row-drawing code — confirmed via direct read, so FX12 must be applied to both separately. `ICON_SIZE_COMPACT=16`/`ICON_SIZE_EXPANDED=40` (line 48-49) are both smaller than row height. `pingStatusColor(long ping)` (line 325) is the existing status-color function to reuse for FX12.4's border. MOTD draw in `renderSaved` currently gated `if (expanded && server.motd != null)` per round-1 FX4.4. No `mouseScrolled` override anywhere in this class or `MainMenuScreen` — confirmed via grep, zero matches.
- `mainmenu/WorldsPanel.java`: round-1 FX2 added a small icon smaller than row height; `editWorld(...)` calls `EditWorldScreen.create(Minecraft.getInstance(), access, backedUp -> { })` — no-op callback (confirmed root cause of FX15). The working reference, `createButton`'s handler, calls `CreateWorldScreen.openFresh(Minecraft.getInstance(), () -> Minecraft.getInstance().setScreenAndShow(owner))`.
- `net.minecraft.client.gui.screens.worldselection.EditWorldScreen` (vanilla, confirmed via `javap` against the 26.2 named/merged client jar): `create(Minecraft, LevelStorageSource.LevelStorageAccess, BooleanConsumer)`; `onClose()` bytecode is exactly `callback.accept(false); return;` — no screen transition of its own. This means the caller-supplied callback is entirely responsible for both post-Save and post-Cancel navigation.
- `friends/FriendSidebarWidget.java`: `extractWidgetRenderState` is a deliberate no-op; real drawing happens in a separate `renderNow(...)` method. `FabricFriendsSidebarInjector` calls `renderNow(...)` via a `ScreenEvents.afterExtract` hook on its own allow-list of vanilla screens — `MainMenuScreen` is a custom screen, not on that allow-list, and never calls `renderNow(...)` itself. Confirmed via direct read this round that round-1's FX1 (null-facade fix) was real and correctly applied, but did not address this — the widget never had its paint method invoked at all, independent of the facade being valid.
- `mainmenu/MainMenuScreen.java`: `extractRenderState` (line ~135) never calls `sidebar.renderNow(...)`.

## Files to modify
Per platform module (×3), all under `src/main/java/de/lazuli/`:

1. **`mainmenu/ServersPanel.java`**
   - FX10: add a scroll-offset field (e.g. `private int browseScrollOffset`), applied to each row's Y position in `renderBrowser`; add a `mouseScrolled(...)` method (or equivalent hook) that adjusts this offset when the Browse sub-view is active and the mouse is within the panel bounds, clamped so it can't scroll past the first/last row; clip the drawn region to the panel's bounds (scissor or manual per-row bounds check) so no partial row bleeds outside the panel.
   - FX11: change the MOTD draw condition from `expanded && server.motd != null` to just `server.motd != null` (drawn in both compact and expanded rows); clip/truncate the MOTD text to the row's available width (panel width minus the new full-height image column from FX12, minus margin) so it can never overflow into the tab bar.
   - FX12: in both `renderSaved` and `renderBrowser`, resize the row image to full row-height (row height minus a 1-2px top/bottom margin), 1:1 aspect ratio; remove the separate ping-status dot draw call; add a 1-2px colored border around the image using the existing `pingStatusColor(long ping)` function's return value (reuse, don't reinvent); grow row-height constants if needed so text/buttons aren't cramped by the larger image column.
2. **`mainmenu/WorldsPanel.java`**
   - FX13: resize the row image to full row-height (same margin convention as FX12), 1:1 aspect ratio, no border; grow row-height constants if needed (independent of `ServersPanel`'s constants, visually consistent treatment).
   - FX15: change `editWorld(...)`'s `EditWorldScreen.create(...)` callback from `backedUp -> { }` to `backedUp -> Minecraft.getInstance().setScreenAndShow(owner)` (same `owner` reference the create-flow already uses) so both Save-completion and Cancel (`onClose()`'s `callback.accept(false)`) navigate back to the main menu. Confirm whether returning to `MainMenuScreen` needs an explicit `worldsPanel.reload()` call in this callback to reflect name/setting changes in the list, following whatever the create-flow's own callback already does for a freshly created world (if it reloads, mirror that; if the panel already reloads itself on next tab-render/`init`, no extra call needed — implementation confirms by reading the create-flow path fully).
3. **`mainmenu/MainMenuScreen.java`**
   - FX14: call `sidebar.renderNow(...)` explicitly from `extractRenderState`, with whatever arguments that method's real signature requires (guiGraphics/mouseX/mouseY/delta — confirm exact signature by reading `FriendSidebarWidget.renderNow` and how `FabricFriendsSidebarInjector` already calls it elsewhere, do not guess). Do NOT add `MainMenuScreen` to `FabricFriendsSidebarInjector`'s allow-list — this screen stays self-contained, calling `renderNow` directly instead of via the injector hook, to avoid any double-render risk if the allow-list is ever touched later.
   - If FX10's scroll-forwarding needs a new entry point from `MainMenuScreen` to the active panel (mirroring how `mouseClicked` already forwards to `worldsPanel`/`serversPanel`/etc.), add it here, forwarding only to `serversPanel` when it's the active tab (Worlds/Store/Wardrobe don't need scroll in this batch unless FX12/FX13's taller world rows also overflow — implementation should check this at verification time and flag if Worlds also needs scrolling, since that would be new-but-related scope, not silently added here).

No changes anticipated to `IconTextureCache`, `MainMenuBackgroundRenderer`, `MainMenuStateMachine`, `MainMenuTab`, `StorePanel`, `WardrobePanel`.

## Risks
- **R1 — FX14's exact `renderNow` signature and 1.21.11/26.1 divergence.** Must be read directly from `FriendSidebarWidget.java` and confirmed on all three platforms before wiring the call — round 1's Compatibility note already flags 1.21.11 as potentially using a different render entrypoint (`DrawContext` vs `GuiGraphicsExtractor`).
- **R2 — FX15's callback semantics for `backedUp`.** The plan assumes both `true`/`false` should navigate back identically; if manual testing reveals `EditWorldScreen`'s Save path expects different callback behavior for backup-made-vs-not (e.g. showing a toast), implementation should preserve any such existing side effect and only fix the missing navigation, not remove functionality.
- **R3 — FX12/FX13 row-height growth cascading into new scroll needs for Worlds/Saved.** Taller rows from full-height images may make previously-fitting lists (Saved sub-view, Worlds panel) newly overflow without scrolling, even though only Browse (FX10) was explicitly called out as needing it. Verification must check this, not assume only Browse needs it.
- **R4 — FX12.6 confirmed: `renderBrowser`/`renderSaved` are independent, not shared code** (confirmed via direct read this planning pass) — FX12's image/border fix must be applied twice, once per method, with no shared helper existing yet to extract into (implementation may extract a small shared row-image-drawing helper if it reduces duplication, but this is a nice-to-have, not required).
- **R5 — FX11's clip/truncate approach.** No existing scissor/clip helper was confirmed in this codebase for bounding text; implementation must find and reuse whatever mechanism `GuiGraphicsExtractor` (or vanilla `GuiGraphics`) already exposes for bounded text (e.g. a max-width text draw variant) rather than inventing manual substring-truncation-by-pixel-width math, unless no such vanilla helper exists.

## Dependencies
No new external dependencies. Reuses existing `IconTextureCache` (round 1), existing `pingStatusColor` function, existing vanilla `EditWorldScreen`/`CreateWorldScreen` APIs, existing `FriendSidebarWidget.renderNow` method.

## Test strategy
- **FX10:** Manual verification — populate more saved/browsed servers than fit the panel height (or resize window smaller), confirm mouse-wheel scrolls the Browse list, confirm it clamps at both ends, confirm no visual bleed outside the panel.
- **FX11:** Manual verification — a server with a long MOTD, confirm it renders in both compact and expanded rows and never overflows into the tab bar area.
- **FX12/FX13:** Manual/visual verification — confirm images are square, full row-height, 1-2px margin; confirm server images have a colored border matching ping status (test at least one server in each ping-status color bucket if reachable); confirm world images have no border.
- **FX14:** Manual verification — open the main menu, confirm the sidebar's collapsed avatar rail is visible immediately (this exact check failed twice now — round 1's fix compiled but didn't resolve the symptom — so this is the single most important manual check in this batch).
- **FX15:** Manual verification — edit a world, change its name, press Save, confirm it returns to the main menu and the world list reflects the new name; separately, edit a world, press Cancel/ESC, confirm it also returns to the main menu with no changes persisted.
- No automated UI test harness exists in this repo (consistent with prior rounds) — manual verification is the primary strategy, plus a Gradle compile check across all three platform modules as an automated floor.

## Acceptance criteria
1. Servers → Browse list scrolls via mouse wheel, reaching all rows, clamped at both ends, no bleed outside the panel.
2. Servers → Saved MOTD always renders (compact and expanded) once a server has been pinged, never overflowing into the tab bar.
3. Server rows show a full row-height 1:1 image with a 1-2px margin and a colored border reflecting ping status; the separate status dot is gone.
4. World rows show a full row-height 1:1 image with the same margin, no border.
5. The friends sidebar's collapsed avatar rail is visible immediately on opening the main menu (this is the substantive fix criterion — round 1's fix alone did not achieve this).
6. Editing a world and pressing Save returns to the main menu with the change reflected in the world list.
7. Editing a world and pressing Cancel/ESC returns to the main menu with no changes persisted.
8. All of the above verified independently on `fabric-1.21.11`, `fabric-26.1`, and `fabric-26.2`.
9. All three platform modules compile cleanly (`./gradlew compileJava`).
