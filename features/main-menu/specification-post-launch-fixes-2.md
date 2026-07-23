# Main Menu — Post-Launch Fixes 2 Specification

Third pass on the main-menu rework (`features/main-menu/specification.md` original; `specification-post-launch-fixes.md`/`plan-post-launch-fixes.md` round 1, FX1-FX9, already implemented and compiling; a standalone crash fix in `MainMenuBackgroundRenderer` character-sizing math was also already applied directly, outside these documents). Six new issues found in manual testing, across all three platform modules (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`).

## Overview
Bugfixes/completions only — no new tabs/panels/persisted-state shapes. Two of these (server list scrolling, world/server row image sizing+status-border) are genuine gaps left over from round 1's scope; two (friends sidebar, world-edit Save/Cancel) are confirmed-still-broken bugs whose root causes were mis-diagnosed or incompletely fixed in round 1.

## Goals
- Make the Servers → Browse list scrollable (mouse wheel), matching the Saved sub-view's and Worlds panel's implied scroll behavior.
- Servers → Saved MOTD always renders (not conditional on expanded state) and is clipped/wrapped to the row's own width instead of overflowing into the tab bar.
- Server row preview images become full row-height at 1:1 aspect ratio with a 1-2px margin; the ping-status color moves from a separate dot to a border drawn around the image.
- World row preview images become full row-height at 1:1 aspect ratio with the same margin, no border (no status concept for worlds).
- Friends sidebar actually paints pixels on the main menu screen (round 1's FX1 fixed a real but different bug — a null `RichPresenceFacade` — that did not address why the sidebar renders nothing).
- World-edit Save and Cancel both actually return to the main menu / world list (currently both are no-ops; new-world-creation Cancel already works correctly and is the reference pattern to copy).

## Non-goals
- Not adding new server-browser columns/sorting beyond making the existing list scrollable.
- Not changing the MOTD's formatting/parsing logic (round 1 FX4.4 already handles color-code stripping) — only visibility-always and clipping.
- Not redesigning row layout beyond the image-sizing/border change (text position, button placement otherwise unchanged except as forced by the wider image column).
- Not adding new friends-sidebar features — only making existing rendering actually occur.
- Not changing `EditWorldScreen`'s own vanilla behavior — only supplying it a correct, non-no-op callback.

## Requirements

### FX10 — Servers → Browse list not scrollable
Current state: `ServersPanel.renderBrowser` iterates candidate rows and `break`s once a row's top would exceed `y + height` — no scissor/clip region, no scroll offset field, and `mouseScrolled`/equivalent is not overridden anywhere in `ServersPanel`, so rows beyond the panel's visible height are simply never drawn and never reachable.
- **FX10.1** `ServersPanel` gains a scroll-offset field (pixels or row-index, implementation's choice) scoped to the Browse sub-view, applied when computing each row's Y position, so scrolling moves which rows are visible within the fixed panel viewport.
- **FX10.2** `ServersPanel` must respond to mouse-wheel input while the Browse sub-view is active and the mouse is over the panel — hook into whatever scroll-input entry point `MainMenuScreen` already forwards to panels (if none exists yet, add one, mirroring how `mouseClicked` is already forwarded from `MainMenuScreen` to the active panel).
- **FX10.3** Scroll offset must be clamped so the list can't scroll past its first or last row (no dead space above row 1, no scrolling indefinitely past the last row).
- **FX10.4** The visible row region must be clipped (scissor or equivalent) to the panel's bounds so a partially-visible row at the top/bottom edge doesn't bleed outside the panel into the tab bar/background regions.

### FX11 — Servers → Saved MOTD: always show + fix overflow into tab bar
Current state: `ServersPanel.renderSaved` only draws MOTD `if (expanded && server.motd != null)` (round 1 FX4.4's implementation), and draws it via an unbounded text call with no width-limiting/clipping, so on wide MOTDs the text runs past the panel's right edge into the tab bar's rendered area.
- **FX11.1** MOTD renders in both compact and expanded row states (not gated on `expanded`), whenever `server.motd != null` — for servers not yet pinged, no MOTD line is expected (empty/no-ping state, consistent with FX4.3's "—"/"Pinging..." placeholder elsewhere), but once a ping resolves, MOTD must always show even in compact rows.
- **FX11.2** MOTD text must be clipped/wrapped to the row's own available width (the panel width minus the image column from FX12, minus any margin) — never drawn past the panel's right edge. Implementation may choose to truncate with ellipsis or wrap to a second line within the row height, whichever fits the row-height budget established by FX12/FX13's full-height image change; must not rely on the panel's translucent background fill alone to visually hide overflow, since the tab bar renders in front of/after the panel in z-order today (confirmed cause of the "overflow into tab bar" symptom).

### FX12 — Server row images: full row-height 1:1, ping-status as border not dot
Current state: `ServersPanel` draws a small fixed-size icon (`ICON_SIZE_COMPACT`/`ICON_SIZE_EXPANDED`, smaller than row height) plus a separate 4×4 ping-status-colored dot next to it.
- **FX12.1** Each server row's image becomes a square exactly as tall as the row (row height minus the FX12.3 margin on both top and bottom, still 1:1 aspect ratio — width equals height).
- **FX12.2** The separate ping-status dot is removed entirely.
- **FX12.3** A 1-2px margin (implementation picks the exact value, consistent across compact/expanded) separates the image from the row's top/bottom edges and from adjacent text/controls.
- **FX12.4** The image is given a colored border (1-2px thick, same margin scale as FX12.3) using the same color mapping the removed dot used for ping status (green/yellow/red/gray or whatever the existing `pingStatusColor` mapping already defines) — border color must come from the same existing status-color function, not a newly invented palette.
- **FX12.5** Row height itself may need to grow to comfortably fit a full-height 1:1 image plus existing text/button content without cramming — implementation's judgment call, consistent between compact and expanded row height constants already in the file.
- **FX12.6** This applies to both Browse and Saved sub-views' rows, since both draw server rows from the same or parallel rendering paths — implementation must confirm whether `renderBrowser` and `renderSaved` share row-drawing code or are independent, and apply the fix to both if independent.

### FX13 — World row images: full row-height 1:1, no border
Current state: `WorldsPanel` draws a small icon (round 1 FX2's fix) sized smaller than the row.
- **FX13.1** Each world row's image becomes a square exactly as tall as the row (minus the same top/bottom margin as FX12.3), 1:1 aspect ratio.
- **FX13.2** No border — worlds have no ping/status concept, so unlike FX12.4, no colored border is added.
- **FX13.3** Row height may grow to fit, same judgment call as FX12.5, applied consistently to `WorldsPanel`'s own compact/expanded row constants (independent of `ServersPanel`'s row-height constants — they need not match numerically, but both should look visually consistent as "the same treatment" per the user's framing).

### FX14 — Friends sidebar still invisible (real root cause, distinct from round-1 FX1)
Round-1 FX1 fixed a genuine bug (null `RichPresenceFacade` passed to `FriendSidebarWidget`'s constructor) but this did **not** resolve the visible symptom, because it was never the actual cause of invisibility. Confirmed via direct code reading this round:
- `MainMenuScreen` constructs `sidebar` with a real, non-null `RichPresenceFacade` and adds it via `addRenderableWidget(sidebar)` in `init()` — the FX1 fix is genuinely in place and correct as far as it goes.
- `FriendSidebarWidget.extractWidgetRenderState` is a deliberate no-op by this widget's own design — all real drawing happens in a separate `renderNow(...)` method, which on every other allow-listed vanilla screen (per the original friends-sidebar feature's own injection design) is invoked externally by `FabricFriendsSidebarInjector`'s `ScreenEvents.afterExtract` hook for that specific screen instance.
- `MainMenuScreen.extractRenderState` never calls `sidebar.renderNow(...)` anywhere, and `MainMenuScreen` is presumably not on `FabricFriendsSidebarInjector`'s allow-list of screens it hooks (since that injector is designed around vanilla screen classes, not this mod's own custom `MainMenuScreen`). Net effect: the widget exists, receives input/hover events, but never paints a single pixel.
- **FX14.1** `MainMenuScreen.extractRenderState` must explicitly call `sidebar.renderNow(...)` (with whatever arguments that method requires — guiGraphics/mouseX/mouseY/delta, matching the signature `FabricFriendsSidebarInjector` already calls it with elsewhere) as part of its own render pass, rather than relying on the external injector hook that isn't wired to this screen.
- **FX14.2** Confirm whether `FabricFriendsSidebarInjector`'s allow-list mechanism would double-render the sidebar if `MainMenuScreen` were later added to it — implementation should NOT add `MainMenuScreen` to that allow-list; the explicit in-screen `renderNow` call is the correct fix, keeping `MainMenuScreen` as a self-contained, non-injector-managed case (consistent with it being a custom screen with its own render pipeline, not a vanilla screen the injector patches).
- **FX14.3** This must be verified identically on `fabric-26.1` (likely mirrors `fabric-26.2`'s `GuiGraphicsExtractor`-based render model) and `fabric-1.21.11` (may use a different render entrypoint/method name per round 1's own Compatibility note — confirm the equivalent call there, don't assume identical method names).

### FX15 — World-edit Save/Cancel do nothing (root cause confirmed via bytecode inspection)
Confirmed via `javap` against the real `net.minecraft.client.gui.screens.worldselection.EditWorldScreen` class (26.2 mappings):
- `EditWorldScreen.create(Minecraft, LevelStorageSource.LevelStorageAccess, BooleanConsumer)` takes a `BooleanConsumer callback`.
- `EditWorldScreen.onClose()` (the screen's Cancel/ESC handling) does exactly one thing: `callback.accept(false)` — it does **not** itself call `Minecraft.setScreen(...)` or any other screen transition. The screen transition on Cancel is expected to happen *inside* the caller-supplied callback.
- `WorldsPanel.editWorld` currently calls `EditWorldScreen.create(Minecraft.getInstance(), access, backedUp -> { })` — a no-op callback. Since neither Save's completion path nor `onClose()`'s Cancel path do anything beyond invoking this callback, and the callback does nothing, **both Save and Cancel correctly reach the callback but the callback itself performs no screen transition or persistence follow-up** — this is the confirmed, complete root cause.
- The working reference pattern already in the same file: `WorldsPanel`'s "+ Create New World" button handler calls `CreateWorldScreen.openFresh(Minecraft.getInstance(), () -> Minecraft.getInstance().setScreenAndShow(owner))`, where `owner` is the `MainMenuScreen` instance — i.e. it explicitly navigates back to the main menu screen on completion.
- **FX15.1** `WorldsPanel.editWorld`'s callback must be changed from `backedUp -> { }` to a callback that calls `Minecraft.getInstance().setScreenAndShow(owner)` (same `owner`/`MainMenuScreen` reference the create-flow already uses), so both Save-then-return and Cancel-then-return actually navigate back to the main menu.
- **FX15.2** The `boolean backedUp` parameter itself (whether a world backup was made before edits) does not need to drive different behavior per FX15.1 — both `true` and `false` should navigate back the same way, matching how `EditWorldScreen`'s own `onClose()` unconditionally passes `false` with no special-casing.
- **FX15.3** After navigating back, the world list must reflect any name/settings changes made in the edit screen — confirm whether `WorldsPanel.reload()` (or equivalent) needs to be called explicitly in the callback, or whether returning to `MainMenuScreen` already triggers a fresh `WorldsPanel.init`/`reload()` on next tab-render (implementation's judgment, based on how the create-flow's callback already handles this, if it does).
- **FX15.4** Verify this on all three platform modules — `EditWorldScreen`'s API shape (constructor/`create`/`onClose` semantics) is core vanilla client code and should be structurally similar across 1.21.11/26.1/26.2, but implementation must confirm via each version's own mappings/`javap` rather than assuming, per round 1's Compatibility note about non-identical platform copies.

## Public API
No new public API surface. FX14.1 is an internal call added inside `MainMenuScreen.extractRenderState`. FX15.1 changes a lambda body in `WorldsPanel.editWorld`, not its signature.

## Architecture
No new classes. FX10's scroll state is a new field on the existing `ServersPanel`. FX12/FX13's image-sizing changes are confined to existing row-rendering code in `ServersPanel`/`WorldsPanel`, reusing the `IconTextureCache` from round 1 (no changes needed to that cache itself — only how large a region its returned texture is drawn into).

## UI
FX12/FX13's row-height growth (to fit full-height images) may cascade into fewer rows fitting the same panel height without scrolling — this makes FX10's Browse-scrolling fix more load-bearing, not less; implementation should treat FX10 and FX12 as interacting (taller rows means scrolling matters even for previously-fitting lists) and verify both together, not sequentially in isolation.

## Configuration
No configuration changes.

## Events
No new events.

## Networking
No networking changes — FX11's MOTD-always-show relies on the same ping data round 1's FX4.5 already fetches.

## Persistence
No persistence-format changes. FX15's world-edit save behavior relies entirely on `EditWorldScreen`'s own existing vanilla persistence (name/settings writes), not new persistence code in this mod.

## Compatibility
All six fixes apply across `platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`. FX14 (sidebar) and FX15 (edit-world) both require per-platform confirmation of exact method names/signatures (`renderNow`-equivalent, `EditWorldScreen`-equivalent) rather than assuming identical shapes, consistent with round 1's established divergence risk (1.21.11 uses an older `DrawContext`-based render model per `MainMenuBackgroundRenderer`'s existing platform-specific code).

## Performance
FX10's scrolling must not re-fetch/re-ping server data on every scroll tick — only recompute which already-loaded rows are visible. No other performance-sensitive changes in this batch.

## Future Extensions
None beyond what round 1 already listed.
