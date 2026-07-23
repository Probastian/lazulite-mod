# Main Menu — Post-Launch Fixes Specification

Follow-up to `features/main-menu/specification.md` (original "Stonebound" main menu rework, commit `0d71821`). Nine defects found in manual testing, spanning all three platform modules (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`).

## Overview
Bugfixes/completions to the existing `MainMenuScreen`/panel classes — no new tabs, no new panels, no new persisted state shapes.

## Goals
- Restore the friends sidebar to visible/working (FR7 of the original spec).
- Give Worlds and Servers-Saved rows real preview art instead of nothing.
- Confine "+ Create New World" to the Worlds tab only.
- Bring the Servers → Saved sub-view up to functional parity (preview image, refresh, player count, MOTD).
- Remove fabricated placeholder title/version text.
- Fix the panel/background/character layout so the left third of the screen is reserved for the 3D background+character.
- Enlarge/reposition the 3D idle character to match design intent.
- Correct the 3D background's fill/position bug, to the extent it's a layout bug rather than an inherent placeholder-model limitation.
- Give every clickable control the vanilla UI click sound.

## Non-goals
- Not replacing the placeholder character/scene with a real authored 3D asset — only its size/position.
- Not adding new Store/Wardrobe functionality.
- Not changing `MainMenuStateMachine`'s state shape or persistence format.
- Not redesigning the tab bar's visual style beyond what's needed to free up the left third of the screen.
- Not building a full server-MOTD-formatting/color-code renderer beyond what vanilla already exposes.

## Requirements

### FX1 — Friends sidebar invisible
Root cause: `MainMenuScreen`'s constructor (`MainMenuScreen.java:79-80`) constructs its `FriendSidebarWidget` with a **null** `RichPresenceFacade`. `FriendSidebarWidget.extractWidgetRenderState` (~line 420) unconditionally calls `richPresenceFacade.localPresenceStatus()` when drawing the pinned own-profile row, throwing an NPE whenever a local profile is present.
- **FX1.1** `MainMenuScreen` must be constructed with a real, non-null `RichPresenceFacade` instead of `null`.
- **FX1.2** `MainMenuScreen`'s constructor signature (or `MainMenuScreenFactoryHandoff` wiring) gains whatever's needed to supply that facade, consistent with the existing constructor-injection pattern already used for other dependencies.
- **FX1.3** No required change to `FriendSidebarWidget` itself, unless a null-guard is added as optional defense-in-depth.
- **FX1.4** After the fix, the sidebar's collapsed avatar rail must be visible immediately on menu open, expanding on hover per existing behavior, correctly positioned relative to the tab bar (depends on FX6's layout changes).

### FX2 — Worlds have no preview image
- **FX2.1** Compact rows draw a single square thumbnail to the left of name/subtitle, sourced from the world's `icon.png` via vanilla's own icon-loading mechanism.
- **FX2.2** Expanded rows draw a larger version of the same icon.
- **FX2.3** Worlds with no `icon.png` fall back to vanilla's own default/unknown world icon texture — never a blank/missing-texture box.
- **FX2.4** Icon textures are cached (not reloaded from disk every frame), keyed by level ID, invalidated on `WorldsPanel.reload()`.

### FX3 — "+ Create New World" button visible on all tabs
`WorldsPanel.init` (`WorldsPanel.java:67-72`) adds `createButton` unconditionally, with no visibility-toggle tied to the active tab — unlike `ServersPanel`, which already has a `tabActive`/`applyVisibility()` pattern.
- **FX3.1** `WorldsPanel` gains the same "visibility follows active tab" mechanism (`setTabActive(boolean)` toggling `createButton.visible`), called from `MainMenuScreen.mouseClicked` alongside the existing `serversPanel.setTabActive(...)` call.
- **FX3.2** On fresh screen construction, `createButton.visible` defaults to match whichever tab is actually active by default (confirm via `MainMenuStateMachine`, don't assume Worlds).

### FX4 — Servers → Saved sub-view missing preview image, refresh button, player count, MOTD
`ServersPanel.renderSaved` (`ServersPanel.java:211-243`) draws only a ping-status dot, name, and player count text (blank when unpinged) — no icon, no MOTD, no refresh control, and `savedServers` is never pinged anywhere.
- **FX4.1** Each Saved row shows the server's favicon (vanilla's own per-server icon mechanism), with a generic fallback for servers with no favicon yet.
- **FX4.2 (finalized)** A **separate, Saved-view-specific** refresh `Button` is added, distinct from the existing Browser-only `refreshButton` — not shared/repurposed. Visibility follows `state.serversSubView() == SAVED` (mirroring how the existing `refreshButton`'s visibility follows `== BROWSER`). Its click handler re-pings every saved server via the same underlying vanilla ping mechanism used by FX4.5, not `ServerBrowserSession.refresh()` (which is Browser-only).
- **FX4.3** Player count renders "—"/"Pinging..." instead of blank when `server.players == null`.
- **FX4.4** MOTD renders per row, compact and expanded, using vanilla's own formatting-code parsing.
- **FX4.5** Saved servers are pinged once automatically the first time the Saved sub-view becomes visible (mirroring vanilla's Multiplayer screen), not only on manual refresh.

### FX5 — Remove fabricated placeholder text
`MainMenuScreen.renderTitle` (`MainMenuScreen.java:145-151`) draws literal invented strings "STONEBOUND" and "OVERHAUL MOD · V2.1".
- **FX5.1** Remove both lines (and the method itself if nothing else calls it).
- **FX5.2** No replacement text is required — removal only. A real title/version is a separate future decision if wanted.
- **FX5.3** Class-level Javadoc references to "Stonebound" are documentation only, not required to change.

### FX6 — Panel too wide, eating into the left-third 3D-background region (finalized)
- **FX6.1** The reserved left region for background+character-only is fixed at exactly **1/3 of screen width** (`reservedWidth = width / 3`) — a hard constant for this pass, may be revisited later.
- **FX6.2** The content panel has width-growth priority: the reserved region's width is anchored at `width / 3` and does not itself grow; `panelWidth()` is the flexible region absorbing all remaining space. On any tradeoff (e.g. very small window widths), the reserved-background region shrinks/clips first, never the panel.
- **FX6.3** `panelX()` becomes `reservedWidth` (`width / 3`), replacing the current fixed `24`.
- **FX6.4** `panelWidth()` recomputed from the new `panelX()`, same subtraction terms otherwise (tab bar + sidebar reservation).
- **FX6.5** The panel's translucent background fill must not extend left of the new `panelX()` (no more `x - 12` bleed).
- **FX6.6** No change to `TAB_BAR_WIDTH`/`sidebarCollapsedWidth()` on the right.

### FX7 — 3D character too small / wrong position
- **FX7.1** Character size scales from the reserved left-third region's actual pixel width/height, not the current `Math.max(160, screenHeight/2)` formula which ignores available width.
- **FX7.2** Character horizontal position is centered/bottom-left *within the reserved region*, not `screenWidth * 0.08` (which today can land inside the panel depending on resolution).
- **FX7.3** Vertical anchor re-tuned to stay grounded on the (possibly relocated, per FX8) ground plane rather than floating/clipping.

### FX8 — 3D background too small, centered, stuck to bottom
The destination rect in the `skin(...)` call is already full-screen (`0,0` to `screenWidth,screenHeight`) — the symptom is a scale/camera-framing tuning issue in `buildScene()`'s fixed model-space extents and the `skin()` call's `scale`/pivot parameters, not a destination-rectangle bug.
- **FX8.1** Tune `scale` (and potentially model box extents in `buildScene()`) so the scene visually fills the destination rect at typical aspect ratios.
- **FX8.2** Tune the ground plane's model-space Y offset and camera pivot to center the horizon rather than pushing it to the bottom edge.
- **FX8.3 (finalized)** Attempt real scale/pivot tuning first; only fall back to "placeholder-model limitation" framing (Future Extension) if concrete tuning attempts don't resolve it. Timebox this rather than iterating unboundedly.
- **FX8.4** Re-check after FX6/FX7 land, since background scale/pivot, character framing, and panel left edge all interact within the same region.

### FX9 — Missing click sound on button clicks
Real vanilla `Button` widgets should already play `SoundEvents.UI_BUTTON_CLICK` automatically via `AbstractButton`. The confirmed gaps are hand-drawn, manually-hit-tested regions:
- Tab-bar buttons (`MainMenuScreen.renderTabBar`/`mouseClicked`).
- `WorldsPanel`'s Play/Edit buttons.
- `ServersPanel`'s Saved-sub-view Connect button.
- `ServersPanel`'s Browser-sub-view column-header sort clicks and row-join clicks.
- **FX9.1** Every listed custom-hit-tested region must play the same vanilla UI click sound on click.
- **FX9.2** If implementation/testing finds a real `Button` widget silently not playing sound in some tab state, fix that too, but it's not a confirmed defect from static reading alone.

## Public API
`MainMenuScreen`'s constructor gains whatever's needed for FX1.2 (new parameter or resolved via `MainMenuScreenFactoryHandoff`). No other public API changes.

## Architecture
FX2 and FX4 share one small `IconTextureCache` utility (per platform module) rather than duplicating icon-loading/caching logic twice, similar in spirit to the existing `AvatarTextureCache`.

## UI
FX6/FX7/FX8 together redefine the screen's horizontal layout as one coherent pass: `[reserved left-third: background+character only] [content panel, priority-growth] [tab bar] [sidebar]`.

## Configuration
No configuration changes.

## Events
No new events.

## Networking
FX4 uses vanilla's own existing server-ping networking (status-request packet), not new networking code. Must not block the render thread.

## Persistence
No persistence-format changes.

## Compatibility
All nine fixes apply identically across `platform/fabric-1.21.11`, `platform/fabric-26.1`, and `platform/fabric-26.2` — three parallel copies of every affected class. Implementation must confirm per-platform divergence (e.g. 1.21.11 may use an older direct-`GuiGraphics` render model vs. 26.x's `GuiGraphicsExtractor` pipeline) rather than assuming a mechanical find-replace works across all three.

## Performance
Icon caching (FX2/FX4) must avoid re-reading/re-uploading files every frame. Ping-on-open (FX4.5) must not block the render thread.

## Future Extensions
- A real title/version string, if wanted later.
- A fully-authored 3D background/character model, superseding FX7/FX8's placeholder tuning.
- Multi-shot world preview screenshots (beyond FX2's single-icon interim fix).
